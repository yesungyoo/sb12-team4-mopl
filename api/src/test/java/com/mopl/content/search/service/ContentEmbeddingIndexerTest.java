package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.config.AiProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;

@ExtendWith(MockitoExtension.class)
class ContentEmbeddingIndexerTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentTagRepository contentTagRepository;

    @Mock
    private ContentSearchRepository contentSearchRepository;

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private IndexOperations indexOperations;

    @Mock
    private ContentEmbeddingService contentEmbeddingService;

    @Mock
    private AiProperties aiProperties;

    @Mock
    private Content content;

    @Mock
    private ContentTag contentTag;

    @Mock
    private ContentSearchDocument document;

    @Mock
    private Page<Content> contentPage;

    private ContentEmbeddingIndexer contentEmbeddingIndexer;

    @BeforeEach
    void setUp() {
        contentEmbeddingIndexer =
                createIndexer(true);
    }

    @Test
    void doesNothingWhenAiIsDisabled() {
        ContentEmbeddingIndexer disabledIndexer =
                createIndexer(false);

        disabledIndexer.index(content);

        long indexedCount =
                disabledIndexer.reindexAll();

        assertThat(indexedCount).isZero();

        verifyNoInteractions(
                contentRepository,
                contentTagRepository,
                contentSearchRepository,
                elasticsearchOperations,
                contentEmbeddingService
        );
    }

    @Test
    void doesNothingWhenApiKeyIsMissing() {
        when(aiProperties.apiKey())
                .thenReturn(" ");

        contentEmbeddingIndexer.index(content);

        long indexedCount =
                contentEmbeddingIndexer.reindexAll();

        assertThat(indexedCount).isZero();

        verifyNoInteractions(
                contentRepository,
                contentTagRepository,
                contentSearchRepository,
                elasticsearchOperations,
                contentEmbeddingService
        );
    }

    @Test
    void updatesOnlyEmbeddingForExistingDocument() {
        enableAi();

        UUID contentId = UUID.randomUUID();
        IndexCoordinates indexCoordinates =
                IndexCoordinates.of("contents");
        List<ContentTag> contentTags =
                List.of(contentTag);
        List<Double> embedding =
                List.of(0.1D, 0.2D);

        when(content.getId())
                .thenReturn(contentId);

        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);

        when(contentSearchRepository.findById(contentId.toString()))
                .thenReturn(Optional.of(document));

        when(contentEmbeddingService.embedContent(
                content,
                contentTags
        )).thenReturn(embedding);

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(indexCoordinates);

        contentEmbeddingIndexer.index(content);

        ArgumentCaptor<UpdateQuery> updateQueryCaptor =
                ArgumentCaptor.forClass(UpdateQuery.class);

        InOrder indexingOrder =
                inOrder(
                        contentTagRepository,
                        contentSearchRepository,
                        contentEmbeddingService,
                        elasticsearchOperations
                );

        indexingOrder.verify(contentTagRepository)
                .findAllByContentId(contentId);

        indexingOrder.verify(contentSearchRepository)
                .findById(contentId.toString());

        indexingOrder.verify(contentEmbeddingService)
                .embedContent(content, contentTags);

        indexingOrder.verify(elasticsearchOperations)
                .getIndexCoordinatesFor(
                        ContentSearchDocument.class
                );

        indexingOrder.verify(elasticsearchOperations)
                .update(
                        updateQueryCaptor.capture(),
                        same(indexCoordinates)
                );

        UpdateQuery updateQuery =
                updateQueryCaptor.getValue();

        assertThat(updateQuery.getId())
                .isEqualTo(contentId.toString());

        assertThat(updateQuery.getDocument())
                .containsOnlyKeys("embedding");

        assertThat(
                updateQuery.getDocument()
                        .get("embedding")
        ).isEqualTo(List.of(0.1F, 0.2F));

        assertThat(updateQuery.getDocAsUpsert())
                .isNotEqualTo(Boolean.TRUE);

        assertThat(updateQuery.getUpsert())
                .isNull();

        assertThat(updateQuery.getRetryOnConflict())
                .isEqualTo(3);

        assertThat(updateQuery.getRefreshPolicy())
                .isEqualTo(RefreshPolicy.IMMEDIATE);

        verifyNoInteractions(document);

        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));
    }

    @Test
    void doesNotUpdateWhenEmbeddingIsNull() {
        assertMissingEmbeddingIsNotUpdated(null);
    }

    @Test
    void doesNotUpdateWhenEmbeddingIsEmpty() {
        assertMissingEmbeddingIsNotUpdated(List.of());
    }

    @Test
    void doesNotPropagateEmbeddingGenerationFailure() {
        enableAi();

        UUID contentId = UUID.randomUUID();
        List<ContentTag> contentTags =
                List.of(contentTag);

        when(content.getId())
                .thenReturn(contentId);

        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);

        when(contentSearchRepository.findById(contentId.toString()))
                .thenReturn(Optional.of(document));

        when(contentEmbeddingService.embedContent(
                content,
                contentTags
        )).thenThrow(
                new RuntimeException("OpenAI unavailable")
        );

        assertThatCode(
                () -> contentEmbeddingIndexer.index(content)
        ).doesNotThrowAnyException();

        verifyNoInteractions(
                document,
                elasticsearchOperations
        );

        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));
    }

    @Test
    void doesNotPropagateEmbeddingUpdateFailure() {
        enableAi();

        UUID contentId = UUID.randomUUID();
        IndexCoordinates indexCoordinates =
                IndexCoordinates.of("contents");
        List<ContentTag> contentTags =
                List.of(contentTag);
        List<Double> embedding =
                List.of(0.1D, 0.2D);

        when(content.getId())
                .thenReturn(contentId);

        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);

        when(contentSearchRepository.findById(contentId.toString()))
                .thenReturn(Optional.of(document));

        when(contentEmbeddingService.embedContent(
                content,
                contentTags
        )).thenReturn(embedding);

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(indexCoordinates);

        when(elasticsearchOperations.update(
                any(UpdateQuery.class),
                same(indexCoordinates)
        ))
                .thenThrow(
                        new RuntimeException(
                                "Elasticsearch unavailable"
                        )
                );

        assertThatCode(
                () -> contentEmbeddingIndexer.index(content)
        ).doesNotThrowAnyException();

        verifyNoInteractions(document);

        verify(elasticsearchOperations)
                .update(
                        any(UpdateQuery.class),
                        same(indexCoordinates)
                );

        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));
    }

    @Test
    void reindexAllReturnsSuccessfullyIndexedEmbeddingCount() {
        enableAi();

        Content firstContent = mock(Content.class);
        Content secondContent = mock(Content.class);
        Content thirdContent = mock(Content.class);

        ContentTag firstContentTag =
                mock(ContentTag.class);

        ContentSearchDocument firstDocument =
                mock(ContentSearchDocument.class);
        ContentSearchDocument secondDocument =
                mock(ContentSearchDocument.class);
        ContentSearchDocument thirdDocument =
                mock(ContentSearchDocument.class);

        UUID firstContentId = UUID.randomUUID();
        UUID secondContentId = UUID.randomUUID();
        UUID thirdContentId = UUID.randomUUID();
        IndexCoordinates indexCoordinates =
                IndexCoordinates.of("contents");

        List<Content> contents = List.of(
                firstContent,
                secondContent,
                thirdContent
        );

        List<UUID> contentIds = List.of(
                firstContentId,
                secondContentId,
                thirdContentId
        );

        List<String> documentIds = contentIds.stream()
                .map(UUID::toString)
                .toList();

        List<Double> firstEmbedding =
                List.of(0.1D, 0.2D);

        when(firstContent.getId())
                .thenReturn(firstContentId);
        when(secondContent.getId())
                .thenReturn(secondContentId);
        when(thirdContent.getId())
                .thenReturn(thirdContentId);

        when(contentPage.getContent())
                .thenReturn(contents);
        when(contentPage.hasNext())
                .thenReturn(false);

        when(contentRepository.findAllByDeletedAtIsNull(
                PageRequest.of(
                        0,
                        500,
                        Sort.by(Sort.Direction.ASC, "id")
                )
        )).thenReturn(contentPage);

        when(contentTagRepository.findAllByContentIds(contentIds))
                .thenReturn(List.of(firstContentTag));

        when(firstContentTag.getContent())
                .thenReturn(firstContent);

        when(contentSearchRepository.findAllById(documentIds))
                .thenReturn(List.of(
                        firstDocument,
                        secondDocument,
                        thirdDocument
                ));

        when(firstDocument.getId())
                .thenReturn(firstContentId.toString());
        when(secondDocument.getId())
                .thenReturn(secondContentId.toString());
        when(thirdDocument.getId())
                .thenReturn(thirdContentId.toString());

        when(contentEmbeddingService.embedContent(
                firstContent,
                List.of(firstContentTag)
        )).thenReturn(firstEmbedding);

        when(contentEmbeddingService.embedContent(
                secondContent,
                List.of()
        )).thenThrow(
                new RuntimeException("OpenAI unavailable")
        );

        when(contentEmbeddingService.embedContent(
                thirdContent,
                List.of()
        )).thenReturn(List.of());

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(indexCoordinates);

        when(elasticsearchOperations.indexOps(
                ContentSearchDocument.class
        )).thenReturn(indexOperations);

        doThrow(
                new RuntimeException(
                        "Elasticsearch refresh unavailable"
                )
        ).when(indexOperations)
                .refresh();

        long indexedCount =
                contentEmbeddingIndexer.reindexAll();

        assertThat(indexedCount).isEqualTo(1L);

        verify(contentRepository)
                .findAllByDeletedAtIsNull(
                        PageRequest.of(
                                0,
                                500,
                                Sort.by(
                                        Sort.Direction.ASC,
                                        "id"
                                )
                        )
                );

        verify(contentTagRepository)
                .findAllByContentIds(contentIds);

        verify(contentSearchRepository)
                .findAllById(documentIds);

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
                .isEqualTo(firstContentId.toString());

        assertThat(updateQuery.getDocument())
                .containsOnlyKeys("embedding");

        assertThat(updateQuery.getRefreshPolicy())
                .isEqualTo(RefreshPolicy.NONE);

        verify(indexOperations)
                .refresh();

        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));
    }

    private ContentEmbeddingIndexer createIndexer(
            boolean aiEnabled
    ) {
        return new ContentEmbeddingIndexer(
                contentRepository,
                contentTagRepository,
                contentSearchRepository,
                elasticsearchOperations,
                contentEmbeddingService,
                aiProperties,
                aiEnabled
        );
    }

    private void enableAi() {
        when(aiProperties.apiKey())
                .thenReturn("test-api-key");
    }

    private void assertMissingEmbeddingIsNotUpdated(
            List<Double> embedding
    ) {
        enableAi();

        UUID contentId = UUID.randomUUID();
        List<ContentTag> contentTags =
                List.of(contentTag);

        when(content.getId())
                .thenReturn(contentId);

        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);

        when(contentSearchRepository.findById(contentId.toString()))
                .thenReturn(Optional.of(document));

        when(contentEmbeddingService.embedContent(
                content,
                contentTags
        )).thenReturn(embedding);

        contentEmbeddingIndexer.index(content);

        verifyNoInteractions(
                document,
                elasticsearchOperations
        );

        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));
    }
}
