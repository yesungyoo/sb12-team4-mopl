package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
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
import com.mopl.infrastructure.ai.config.AiAvailability;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
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
    void reindexAllQueriesOnlyMissingEmbeddingsAndCountsOutcomes() {
        enableAi();

        Content firstContent = mock(Content.class);
        Content secondContent = mock(Content.class);
        Content thirdContent = mock(Content.class);
        UUID firstContentId = UUID.randomUUID();
        UUID secondContentId = UUID.randomUUID();
        UUID thirdContentId = UUID.randomUUID();
        UUID deletedContentId = UUID.randomUUID();
        IndexCoordinates indexCoordinates =
                IndexCoordinates.of("contents");
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(List.of(
                        firstContentId,
                        secondContentId,
                        thirdContentId,
                        deletedContentId
                ));

        when(firstContent.getId())
                .thenReturn(firstContentId);
        when(secondContent.getId())
                .thenReturn(secondContentId);
        when(thirdContent.getId())
                .thenReturn(thirdContentId);
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(
                        firstContentId,
                        secondContentId,
                        thirdContentId,
                        deletedContentId
                )
        )).thenReturn(List.of(firstContent, secondContent, thirdContent));
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());

        when(contentEmbeddingService.embedContent(
                firstContent,
                List.of()
        )).thenReturn(List.of(0.1D, 0.2D));

        when(contentEmbeddingService.embedContent(
                secondContent,
                List.of()
        )).thenThrow(
                new RuntimeException("OpenAI unavailable")
        );

        when(contentEmbeddingService.embedContent(
                thirdContent,
                List.of()
        )).thenReturn(List.of(0.3D, 0.4D));

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(indexCoordinates);

        when(elasticsearchOperations.indexOps(
                ContentSearchDocument.class
        )).thenReturn(indexOperations);

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(2L);
        assertThat(result.failed()).isEqualTo(1L);
        assertThat(result.skipped()).isEqualTo(1L);

        ArgumentCaptor<NativeQuery> queryCaptor =
                ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).searchForStream(
                queryCaptor.capture(),
                eq(ContentSearchDocument.class)
        );
        assertThat(queryCaptor.getValue().getQuery().bool().mustNot())
                .singleElement()
                .satisfies(mustNot -> assertThat(mustNot.exists().field())
                        .isEqualTo("embedding"));
        assertThat(queryCaptor.getValue().getSourceFilter().getIncludes())
                .containsExactly("id");
        assertThat(queryCaptor.getValue().getSourceFilter().getExcludes())
                .isEmpty();

        ArgumentCaptor<UpdateQuery> updateQueryCaptor =
                ArgumentCaptor.forClass(UpdateQuery.class);

        verify(elasticsearchOperations, times(2))
                .update(
                        updateQueryCaptor.capture(),
                        same(indexCoordinates)
                );

        assertThat(updateQueryCaptor.getAllValues())
                .extracting(UpdateQuery::getId)
                .containsExactlyInAnyOrder(
                        firstContentId.toString(),
                        thirdContentId.toString()
                );
        assertThat(updateQueryCaptor.getAllValues())
                .allSatisfy(updateQuery -> {
                    assertThat(updateQuery.getDocument())
                            .containsOnlyKeys("embedding");
                    assertThat(updateQuery.getRefreshPolicy())
                            .isEqualTo(RefreshPolicy.NONE);
                });

        verify(contentEmbeddingService, times(3))
                .embedContent(any(Content.class), anyList());
        verify(contentRepository)
                .findAllByIdInAndDeletedAtIsNull(anyList());
        verify(searchHits).close();
        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));

        verify(indexOperations)
                .refresh();
    }

    @Test
    void reindexAllProcessesMoreThanOneSearchBatchWithoutRepeatingHits() {
        enableAi();

        List<UUID> contentIds = java.util.stream.IntStream.range(0, 501)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        assertThat(new java.util.HashSet<>(contentIds)).hasSize(501);
        Map<UUID, Content> contentsById = new java.util.HashMap<>();
        contentIds.forEach(contentId -> {
            Content content = mock(Content.class);
            when(content.getId()).thenReturn(contentId);
            contentsById.put(contentId, content);
        });
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(contentIds);

        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        List<List<UUID>> queriedBatches = new java.util.ArrayList<>();
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenAnswer(invocation -> {
                    List<UUID> batchIds = new java.util.ArrayList<>(
                            invocation.getArgument(0)
                    );
                    queriedBatches.add(batchIds);
                    return batchIds.stream()
                            .map(contentsById::get)
                            .toList();
                });
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(contentEmbeddingService.embedContent(
                any(Content.class),
                anyList()
        )).thenReturn(List.of(0.1D));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(elasticsearchOperations.indexOps(
                ContentSearchDocument.class
        )).thenReturn(indexOperations);

        assertThat(contentEmbeddingIndexer.reindexAll())
                .isEqualTo(501L);

        verify(contentRepository, times(2))
                .findAllByIdInAndDeletedAtIsNull(anyList());
        assertThat(queriedBatches)
                .extracting(List::size)
                .containsExactly(500, 1);
        assertThat(queriedBatches.stream()
                .flatMap(List::stream)
                .toList())
                .containsExactlyElementsOf(contentIds);
        verify(contentEmbeddingService, times(501))
                .embedContent(any(Content.class), anyList());
        verify(elasticsearchOperations, times(501))
                .update(any(UpdateQuery.class), any());
        verify(searchHits).close();
    }

    @Test
    void reindexAllPropagatesStreamFailureAndClosesSearchHits() {
        enableAi();

        SearchHitsIterator<ContentSearchDocument> searchHits =
                mock(SearchHitsIterator.class);
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(searchHits.hasNext())
                .thenThrow(new RuntimeException("Elasticsearch unavailable"));
        when(elasticsearchOperations.indexOps(
                ContentSearchDocument.class
        )).thenReturn(indexOperations);

        assertThatThrownBy(contentEmbeddingIndexer::reindexAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("완료하지 못했습니다.");

        verify(searchHits).close();
        verify(indexOperations).refresh();
        verifyNoInteractions(contentRepository, contentTagRepository,
                contentEmbeddingService);
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
                new AiAvailability(
                        aiProperties,
                        aiEnabled
                )
        );
    }

    private void enableAi() {
        when(aiProperties.apiKey())
                .thenReturn("test-api-key");
    }

    private SearchHitsIterator<ContentSearchDocument> createSearchHitsIterator(
            List<UUID> contentIds
    ) {
        SearchHitsIterator<ContentSearchDocument> searchHits =
                mock(SearchHitsIterator.class);
        List<SearchHit<ContentSearchDocument>> hits = contentIds.stream()
                .map(contentId -> {
                    SearchHit<ContentSearchDocument> hit =
                            mock(SearchHit.class);
                    when(hit.getId()).thenReturn(contentId.toString());
                    return hit;
                })
                .toList();
        AtomicInteger position = new AtomicInteger();

        when(searchHits.hasNext())
                .thenAnswer(invocation -> position.get() < hits.size());
        when(searchHits.next())
                .thenAnswer(invocation -> hits.get(position.getAndIncrement()));

        return searchHits;
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
