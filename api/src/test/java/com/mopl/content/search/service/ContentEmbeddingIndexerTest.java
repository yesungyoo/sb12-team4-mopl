package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.config.AiProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
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
    void backfillMissingQueriesOnlyMissingEmbeddingsAndCountsOutcomes() {
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
        assertThat(queryCaptor.getValue().getPageable().getPageSize())
                .isEqualTo(500);

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
        InOrder backfillOrder = inOrder(searchHits, contentEmbeddingService);
        backfillOrder.verify(searchHits).close();
        backfillOrder.verify(contentEmbeddingService)
                .embedContent(firstContent, List.of());
        verify(contentSearchRepository, never())
                .save(any(ContentSearchDocument.class));

        verify(indexOperations)
                .refresh();
    }

    @Test
    void backfillMissingProcessesMoreThanOneSearchBatchWithoutRepeatingHits() {
        enableAi();

        List<UUID> contentIds = IntStream.range(0, 501)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        Map<UUID, Content> contentsById = new HashMap<>();
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
        List<List<UUID>> queriedBatches = new ArrayList<>();
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenAnswer(invocation -> {
                    List<UUID> batchIds = new ArrayList<>(
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
    void backfillMissingWrapsSecondBatchDatabaseFailureAfterFirstBatchCompletes() {
        enableAi();

        List<UUID> contentIds = IntStream.range(0, 501)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        Map<UUID, Content> contentsById = new HashMap<>();
        contentIds.stream()
                .limit(500)
                .forEach(contentId -> {
                    Content content = mock(Content.class);
                    when(content.getId()).thenReturn(contentId);
                    contentsById.put(contentId, content);
                });
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(contentIds);
        RuntimeException databaseFailure =
                new RuntimeException("MySQL unavailable");
        AtomicInteger repositoryCalls = new AtomicInteger();

        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenAnswer(invocation -> {
                    if (repositoryCalls.incrementAndGet() == 2) {
                        throw databaseFailure;
                    }

                    List<UUID> batchIds = invocation.getArgument(0);
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

        assertThatThrownBy(contentEmbeddingIndexer::backfillMissing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("배치 처리에 실패했습니다.")
                .hasMessageContaining("성공=500")
                .hasCause(databaseFailure);

        verify(contentRepository, times(2))
                .findAllByIdInAndDeletedAtIsNull(anyList());
        verify(contentEmbeddingService, times(500))
                .embedContent(any(Content.class), anyList());
        verify(elasticsearchOperations, times(500))
                .update(any(UpdateQuery.class), any());
        verify(searchHits).close();
        verify(searchHits, times(502)).hasNext();
        verify(searchHits, times(501)).next();
        verifyNoMoreInteractions(searchHits);
    }

    @Test
    void backfillMissingPropagatesStreamFailureWithoutEmbeddingProcessing() {
        enableAi();

        SearchHitsIterator<ContentSearchDocument> searchHits =
                mock(SearchHitsIterator.class);
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(searchHits.hasNext())
                .thenThrow(new RuntimeException("Elasticsearch unavailable"));

        assertThatThrownBy(contentEmbeddingIndexer::backfillMissing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("완료하지 못했습니다.");

        verify(searchHits).close();
        verify(indexOperations, never()).refresh();
        verify(elasticsearchOperations, never())
                .update(any(UpdateQuery.class), any());
        verifyNoInteractions(contentRepository, contentTagRepository,
                contentEmbeddingService);
    }

    @Test
    void backfillMissingSkipsInvalidAndDuplicateDocumentIds() {
        enableAi();

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Content first = mock(Content.class);
        Content second = mock(Content.class);
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIteratorFromIds(List.of(
                        firstId.toString(),
                        "not-a-uuid",
                        firstId.toString(),
                        secondId.toString()
                ));
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenReturn(List.of(first, second));
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(contentEmbeddingService.embedContent(any(Content.class), anyList()))
                .thenReturn(List.of(0.1D));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(2L);
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(2L);
        ArgumentCaptor<List<UUID>> idsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(contentRepository)
                .findAllByIdInAndDeletedAtIsNull(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(firstId, secondId);
        verify(contentEmbeddingService, times(2))
                .embedContent(any(Content.class), anyList());
        verify(searchHits).close();
    }

    @Test
    void backfillMissingCountsElasticsearchUpdateFailure() {
        enableAi();
        UUID contentId = UUID.randomUUID();
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(contentId);
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(List.of(contentId));
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenReturn(List.of(content));
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(contentEmbeddingService.embedContent(content, List.of()))
                .thenReturn(List.of(0.1D));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        doThrow(new RuntimeException("Elasticsearch update unavailable"))
                .when(elasticsearchOperations)
                .update(any(UpdateQuery.class), any());
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(1L);
        assertThat(result.skipped()).isZero();
        verify(searchHits).close();
    }

    @Test
    void backfillMissingReportsRefreshFailureAfterSuccessfulUpdates() {
        enableAi();
        UUID contentId = UUID.randomUUID();
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(contentId);
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(List.of(contentId));
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenReturn(List.of(content));
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(contentEmbeddingService.embedContent(content, List.of()))
                .thenReturn(List.of(0.1D));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);
        doThrow(new RuntimeException("refresh unavailable"))
                .when(indexOperations).refresh();

        assertThatThrownBy(contentEmbeddingIndexer::backfillMissing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("업데이트는 처리되었으나")
                .satisfies(exception -> assertThat(exception.getCause())
                        .hasMessage("refresh unavailable"));

        verify(elasticsearchOperations).update(any(UpdateQuery.class), any());
        verify(searchHits).close();
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
        return createSearchHitsIteratorFromIds(
                contentIds.stream().map(UUID::toString).toList()
        );
    }

    private SearchHitsIterator<ContentSearchDocument>
    createSearchHitsIteratorFromIds(
            List<String> documentIds
    ) {
        SearchHitsIterator<ContentSearchDocument> searchHits =
                mock(SearchHitsIterator.class);
        List<SearchHit<ContentSearchDocument>> hits = documentIds.stream()
                .map(documentId -> {
                    SearchHit<ContentSearchDocument> hit =
                            mock(SearchHit.class);
                    when(hit.getId()).thenReturn(documentId);
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
