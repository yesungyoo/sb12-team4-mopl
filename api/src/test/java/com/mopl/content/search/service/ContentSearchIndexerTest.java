package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.repository.ContentViewRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.review.repository.ReviewRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;

@ExtendWith(MockitoExtension.class)
class ContentSearchIndexerTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentTagRepository contentTagRepository;

    @Mock
    private ContentSearchRepository contentSearchRepository;

    @Mock
    private ContentEmbeddingService contentEmbeddingService;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ContentViewRepository contentViewRepository;

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ElasticsearchConverter elasticsearchConverter;

    @Mock
    private IndexOperations indexOperations;

    @InjectMocks
    private ContentSearchIndexer contentSearchIndexer;

    @Test
    void reindexAllRecreatesIndexWithCurrentMapping() {
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);

        when(indexOperations.exists())
                .thenReturn(true);

        when(contentRepository.findAllByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(Page.empty());

        contentSearchIndexer.reindexAll();

        verify(indexOperations).delete();
        verify(indexOperations).createWithMapping();
    }

    @Test
    void indexUsesScriptedUpsertWhilePreservingEmbedding() {
        UUID contentId = UUID.randomUUID();
        Content content = mock(Content.class);
        ContentTag contentTag = mock(ContentTag.class);
        IndexCoordinates indexCoordinates =
                IndexCoordinates.of("contents");

        Document convertedSource =
                Document.create();

        convertedSource.put(
                "type",
                ContentType.MOVIE.name()
        );
        convertedSource.put(
                "title",
                "최신 제목"
        );
        convertedSource.put(
                "tags",
                List.of(
                        Map.of(
                                "tag",
                                "genre",
                                "value",
                                "drama"
                        )
                )
        );
        convertedSource.put(
                "averageRating",
                0.0D
        );
        convertedSource.put(
                "reviewCount",
                0L
        );
        convertedSource.put(
                "watcherCount",
                0L
        );
        convertedSource.put(
                "embedding",
                List.of(0.1F, 0.2F)
        );

        when(content.getId())
                .thenReturn(contentId);
        when(content.getType())
                .thenReturn(ContentType.MOVIE);
        when(content.getTitle())
                .thenReturn("최신 제목");
        when(content.getExternalSource())
                .thenReturn(ExternalSource.MANUAL);

        when(contentTag.getTag())
                .thenReturn("genre");
        when(contentTag.getValue())
                .thenReturn("drama");

        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(List.of(contentTag));

        when(elasticsearchOperations.getElasticsearchConverter())
                .thenReturn(elasticsearchConverter);

        when(elasticsearchConverter.mapObject(
                any(ContentSearchDocument.class)
        )).thenReturn(convertedSource);

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(indexCoordinates);

        contentSearchIndexer.index(content);

        ArgumentCaptor<ContentSearchDocument> sourceDocumentCaptor =
                ArgumentCaptor.forClass(
                        ContentSearchDocument.class
                );

        verify(elasticsearchConverter)
                .mapObject(sourceDocumentCaptor.capture());

        ContentSearchDocument sourceDocument =
                sourceDocumentCaptor.getValue();

        assertThat(sourceDocument.getTitle())
                .isEqualTo("최신 제목");

        assertThat(sourceDocument.getTags())
                .singleElement()
                .satisfies(tag -> {
                    assertThat(tag.getTag())
                            .isEqualTo("genre");
                    assertThat(tag.getValue())
                            .isEqualTo("drama");
                });

        assertThat(sourceDocument.getEmbedding())
                .isNull();

        ArgumentCaptor<UpdateQuery> updateQueryCaptor =
                ArgumentCaptor.forClass(UpdateQuery.class);

        verify(elasticsearchOperations)
                .update(
                        updateQueryCaptor.capture(),
                        same(indexCoordinates)
                );

        UpdateQuery updateQuery =
                updateQueryCaptor.getValue();

        assertThat(updateQuery.getId())
                .isEqualTo(contentId.toString());

        assertThat(updateQuery.getDocument())
                .isNull();

        assertThat(updateQuery.getDocAsUpsert())
                .isNotEqualTo(Boolean.TRUE);

        assertThat(updateQuery.getUpsert())
                .containsEntry(
                        "type",
                        ContentType.MOVIE.name()
                )
                .containsEntry(
                        "title",
                        "최신 제목"
                )
                .containsKey("tags")
                .doesNotContainKey("embedding");

        assertThat(updateQuery.getParams())
                .containsKey("document");

        assertThat(
                updateQuery.getParams()
                        .get("document")
        ).isInstanceOf(Document.class);

        Document scriptSource =
                (Document) updateQuery.getParams()
                        .get("document");

        assertThat(scriptSource)
                .containsEntry(
                        "title",
                        "최신 제목"
                )
                .doesNotContainKey("embedding");

        assertThat(updateQuery.getScriptType())
                .isEqualTo(ScriptType.INLINE);

        assertThat(updateQuery.getScript())
                .contains(
                        "ctx._source.get('embedding')",
                        "ctx._source.clear()",
                        "ctx._source.putAll(params.document)",
                        "ctx._source.put('embedding', embedding)"
                );

        assertThat(updateQuery.getScriptedUpsert())
                .isNotEqualTo(Boolean.TRUE);

        assertThat(updateQuery.getRetryOnConflict())
                .isEqualTo(3);

        assertThat(updateQuery.getRefreshPolicy())
                .isEqualTo(RefreshPolicy.IMMEDIATE);

        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));
    }

    @Test
    void updateStatisticsUpdatesOnlyStatisticsFieldsForExistingDocument() {
        UUID contentId = UUID.randomUUID();
        ContentSearchDocument existingDocument =
                mock(ContentSearchDocument.class);
        IndexCoordinates indexCoordinates =
                IndexCoordinates.of("contents");

        when(contentSearchRepository.findById(contentId.toString()))
                .thenReturn(Optional.of(existingDocument));
        when(reviewRepository.findAverageRatingByContentId(contentId))
                .thenReturn(null);
        when(reviewRepository.countByContentId(contentId))
                .thenReturn(7L);
        when(contentViewRepository.countByContent_Id(contentId))
                .thenReturn(3L);
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(indexCoordinates);

        contentSearchIndexer.updateStatistics(contentId);

        ArgumentCaptor<UpdateQuery> updateQueryCaptor =
                ArgumentCaptor.forClass(UpdateQuery.class);

        verify(elasticsearchOperations)
                .update(
                        updateQueryCaptor.capture(),
                        same(indexCoordinates)
                );

        UpdateQuery updateQuery =
                updateQueryCaptor.getValue();

        assertThat(updateQuery.getId())
                .isEqualTo(contentId.toString());

        assertThat(updateQuery.getDocument())
                .hasSize(3)
                .containsEntry(
                        "averageRating",
                        0.0D
                )
                .containsEntry(
                        "reviewCount",
                        7L
                )
                .containsEntry(
                        "watcherCount",
                        3L
                )
                .doesNotContainKeys(
                        "title",
                        "tags",
                        "embedding"
                );

        assertThat(updateQuery.getUpsert())
                .isNull();

        assertThat(updateQuery.getDocAsUpsert())
                .isNotEqualTo(Boolean.TRUE);

        assertThat(updateQuery.getRetryOnConflict())
                .isEqualTo(3);

        assertThat(updateQuery.getRefreshPolicy())
                .isEqualTo(RefreshPolicy.IMMEDIATE);

        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));
    }

    @Test
    void updateStatisticsDoesNothingWhenDocumentDoesNotExist() {
        UUID contentId = UUID.randomUUID();

        when(contentSearchRepository.findById(contentId.toString()))
                .thenReturn(Optional.empty());

        contentSearchIndexer.updateStatistics(contentId);

        verify(reviewRepository, never())
                .findAverageRatingByContentId(contentId);
        verify(reviewRepository, never())
                .countByContentId(contentId);
        verify(contentViewRepository, never())
                .countByContent_Id(contentId);

        verify(elasticsearchOperations, never())
                .update(
                        any(UpdateQuery.class),
                        any(IndexCoordinates.class)
                );

        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));
    }
}
