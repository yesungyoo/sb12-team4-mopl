package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.BulkFailureException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.BulkOptions;
import org.springframework.data.elasticsearch.core.query.Query;
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

    @Mock
    private SearchHitsIterator<ContentSearchDocument> searchHitsIterator;

    @InjectMocks
    private ContentSearchIndexer contentSearchIndexer;

    @Test
    void reindexAllKeepsExistingIndexAndRefreshesOnce() {
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);

        when(indexOperations.exists())
                .thenReturn(true);

        when(contentRepository.findAllByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(Page.empty());

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));

        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);

        when(searchHitsIterator.hasNext())
                .thenReturn(false);

        long indexedCount = contentSearchIndexer.reindexAll();

        assertThat(indexedCount).isZero();
        verify(indexOperations, never()).delete();
        verify(indexOperations, never()).createWithMapping();
        verify(indexOperations, times(1)).refresh();
        verify(searchHitsIterator).close();
    }

    @Test
    void reindexAllCreatesIndexOnlyWhenItDoesNotExist() {
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);

        when(indexOperations.exists())
                .thenReturn(false);

        when(indexOperations.createWithMapping())
                .thenReturn(true);

        when(contentRepository.findAllByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(Page.empty());

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));

        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);

        when(searchHitsIterator.hasNext())
                .thenReturn(false);

        contentSearchIndexer.reindexAll();

        verify(indexOperations, never()).delete();
        verify(indexOperations).createWithMapping();
        verify(indexOperations).refresh();
        verify(searchHitsIterator).close();
    }

    @Test
    void reindexAllBulkUpsertsEachPageAndRefreshesBeforeIdScan() {
        UUID firstContentId = UUID.randomUUID();
        UUID secondContentId = UUID.randomUUID();
        Content firstContent = createMockContent(
                firstContentId,
                "첫 번째 콘텐츠"
        );
        Content secondContent = createMockContent(
                secondContentId,
                "두 번째 콘텐츠"
        );

        PageRequest firstPageable = PageRequest.of(
                0,
                500,
                Sort.by(Sort.Direction.ASC, "id")
        );
        PageRequest secondPageable = PageRequest.of(
                1,
                500,
                Sort.by(Sort.Direction.ASC, "id")
        );

        when(contentRepository.findAllByDeletedAtIsNull(firstPageable))
                .thenReturn(new PageImpl<>(
                        List.of(firstContent),
                        firstPageable,
                        501
                ));
        when(contentRepository.findAllByDeletedAtIsNull(secondPageable))
                .thenReturn(new PageImpl<>(
                        List.of(secondContent),
                        secondPageable,
                        501
                ));

        stubPageStatistics(List.of(firstContentId));
        stubPageStatistics(List.of(secondContentId));
        stubExistingIndexAndConversion();

        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext()).thenReturn(false);

        long indexedCount = contentSearchIndexer.reindexAll();

        assertThat(indexedCount).isEqualTo(2L);

        verify(contentTagRepository)
                .findAllByContentIds(List.of(firstContentId));
        verify(contentTagRepository)
                .findAllByContentIds(List.of(secondContentId));
        verify(reviewRepository)
                .findStatisticsByContentIds(List.of(firstContentId));
        verify(reviewRepository)
                .findStatisticsByContentIds(List.of(secondContentId));
        verify(contentViewRepository)
                .findStatisticsByContentIds(List.of(firstContentId));
        verify(contentViewRepository)
                .findStatisticsByContentIds(List.of(secondContentId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UpdateQuery>> updateQueriesCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<BulkOptions> bulkOptionsCaptor =
                ArgumentCaptor.forClass(BulkOptions.class);

        verify(elasticsearchOperations, times(2)).bulkUpdate(
                updateQueriesCaptor.capture(),
                bulkOptionsCaptor.capture(),
                eq(IndexCoordinates.of("contents"))
        );

        List<UpdateQuery> updateQueries = updateQueriesCaptor
                .getAllValues()
                .stream()
                .flatMap(List::stream)
                .toList();

        assertThat(updateQueries)
                .extracting(UpdateQuery::getId)
                .containsExactly(
                        firstContentId.toString(),
                        secondContentId.toString()
                );

        assertThat(updateQueries)
                .allSatisfy(updateQuery -> {
                    assertThat(updateQuery.getRefreshPolicy())
                            .isEqualTo(RefreshPolicy.NONE);
                    assertThat(updateQuery.getScriptType())
                            .isEqualTo(ScriptType.INLINE);
                    assertThat(updateQuery.getScript())
                            .contains(
                                    "ctx._source.get('embedding')",
                                    "ctx._source.clear()",
                                    "ctx._source.putAll(params.document)",
                                    "ctx._source.put('embedding', embedding)"
                            );
                    assertThat(updateQuery.getUpsert())
                            .doesNotContainKey("embedding");
                    assertThat(updateQuery.getParams())
                            .containsKey("document");
                    assertThat(
                            (Document) updateQuery.getParams()
                                    .get("document")
                    ).doesNotContainKey("embedding");
                });

        assertThat(bulkOptionsCaptor.getAllValues())
                .allSatisfy(options ->
                        assertThat(options.getRefreshPolicy())
                                .isEqualTo(RefreshPolicy.NONE));

        InOrder executionOrder = inOrder(
                elasticsearchOperations,
                indexOperations
        );
        executionOrder.verify(elasticsearchOperations, times(2))
                .bulkUpdate(
                        anyList(),
                        any(BulkOptions.class),
                        any(IndexCoordinates.class)
                );
        executionOrder.verify(indexOperations, times(1)).refresh();
        executionOrder.verify(elasticsearchOperations)
                .searchForStream(
                        any(Query.class),
                        eq(ContentSearchDocument.class)
                );

        ArgumentCaptor<Query> idQueryCaptor =
                ArgumentCaptor.forClass(Query.class);
        verify(elasticsearchOperations).searchForStream(
                idQueryCaptor.capture(),
                eq(ContentSearchDocument.class)
        );
        assertThat(idQueryCaptor.getValue().getPageable().getPageSize())
                .isEqualTo(500);
        verify(indexOperations, times(1)).refresh();
        verify(searchHitsIterator).close();
        verify(contentSearchRepository, never()).saveAll(any());
    }

    @Test
    void reindexAllDeletesOnlyDocumentMissingFromActiveDatabaseContents() {
        UUID staleContentId = UUID.randomUUID();
        SearchHit<ContentSearchDocument> staleHit =
                createSearchHit(staleContentId);

        stubEmptyContentPageAndExistingIndex();
        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext())
                .thenReturn(true, false);
        when(searchHitsIterator.next())
                .thenReturn(staleHit);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(staleContentId)
        )).thenReturn(List.of());

        contentSearchIndexer.reindexAll();

        verify(contentRepository)
                .findAllByIdInAndDeletedAtIsNull(
                        List.of(staleContentId)
                );
        verify(contentSearchRepository)
                .deleteAllById(
                        List.of(staleContentId.toString())
                );
        verify(indexOperations, times(2)).refresh();
        verify(searchHitsIterator).close();
    }

    @Test
    void reindexAllRechecksAndDeletesCandidatesInBatches() {
        List<UUID> candidateIds = new ArrayList<>();
        List<SearchHit<ContentSearchDocument>> candidateHits =
                new ArrayList<>();

        for (int index = 0; index < 501; index++) {
            UUID contentId = new UUID(0L, index + 1L);
            candidateIds.add(contentId);
            candidateHits.add(createSearchHit(contentId));
        }

        AtomicInteger cursor = new AtomicInteger();

        stubEmptyContentPageAndExistingIndex();
        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext()).thenAnswer(invocation ->
                cursor.get() < candidateHits.size());
        when(searchHitsIterator.next()).thenAnswer(invocation ->
                candidateHits.get(cursor.getAndIncrement()));
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenReturn(List.of());

        contentSearchIndexer.reindexAll();

        List<UUID> firstCandidateBatch = candidateIds.subList(0, 500);
        List<UUID> secondCandidateBatch = candidateIds.subList(500, 501);
        List<String> firstDeleteBatch = firstCandidateBatch.stream()
                .map(UUID::toString)
                .toList();
        List<String> secondDeleteBatch = secondCandidateBatch.stream()
                .map(UUID::toString)
                .toList();

        verify(contentRepository)
                .findAllByIdInAndDeletedAtIsNull(firstCandidateBatch);
        verify(contentRepository)
                .findAllByIdInAndDeletedAtIsNull(secondCandidateBatch);
        verify(contentSearchRepository).deleteAllById(firstDeleteBatch);
        verify(contentSearchRepository).deleteAllById(secondDeleteBatch);
        verify(indexOperations, times(2)).refresh();
        verify(searchHitsIterator).close();
    }

    @Test
    void reindexAllKeepsDeletionCandidateThatBecameActiveBeforeDelete() {
        UUID activeContentId = UUID.randomUUID();
        Content activeContent = mock(Content.class);
        SearchHit<ContentSearchDocument> candidateHit =
                createSearchHit(activeContentId);

        when(activeContent.getId()).thenReturn(activeContentId);
        stubEmptyContentPageAndExistingIndex();
        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext())
                .thenReturn(true, false);
        when(searchHitsIterator.next())
                .thenReturn(candidateHit);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(activeContentId)
        )).thenReturn(List.of(activeContent));

        contentSearchIndexer.reindexAll();

        verify(contentRepository)
                .findAllByIdInAndDeletedAtIsNull(
                        List.of(activeContentId)
                );
        verify(contentSearchRepository, never())
                .deleteAllById(anyList());
        verify(indexOperations, times(1)).refresh();
        verify(searchHitsIterator).close();
    }

    @Test
    void reindexAllValidatesEveryCandidateIdBeforeDeletingAnyDocument() {
        UUID validCandidateId = UUID.randomUUID();
        String invalidDocumentId = "not-a-uuid";
        SearchHit<ContentSearchDocument> validHit =
                createSearchHit(validCandidateId);
        SearchHit<ContentSearchDocument> invalidHit =
                createSearchHit(invalidDocumentId);

        stubEmptyContentPageAndExistingIndex();
        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext()).thenReturn(true, true, false);
        when(searchHitsIterator.next()).thenReturn(
                validHit,
                invalidHit
        );

        assertThatThrownBy(contentSearchIndexer::reindexAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(invalidDocumentId);

        verify(contentRepository, never())
                .findAllByIdInAndDeletedAtIsNull(anyList());
        verify(contentSearchRepository, never()).deleteAllById(anyList());
        verify(indexOperations, times(1)).refresh();
        verify(searchHitsIterator).close();
    }

    @Test
    void reindexAllDoesNotRefreshAgainWhenRecheckedCandidatesAreActive() {
        UUID activeContentId = UUID.randomUUID();
        Content activeContent = mock(Content.class);
        SearchHit<ContentSearchDocument> activeHit =
                createSearchHit(activeContentId);
        when(activeContent.getId()).thenReturn(activeContentId);
        stubEmptyContentPageAndExistingIndex();
        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext()).thenReturn(true, false);
        when(searchHitsIterator.next())
                .thenReturn(activeHit);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(activeContentId)
        )).thenReturn(List.of(activeContent));

        contentSearchIndexer.reindexAll();

        verify(contentSearchRepository, never()).deleteAllById(anyList());
        verify(indexOperations, times(1)).refresh();
    }

    @Test
    void reindexAllPropagatesFinalRefreshFailureAfterDeletingDocuments() {
        UUID staleContentId = UUID.randomUUID();
        SearchHit<ContentSearchDocument> staleHit =
                createSearchHit(staleContentId);
        RuntimeException failure = new RuntimeException(
                "Elasticsearch 삭제 후 refresh 실패"
        );
        stubEmptyContentPageAndExistingIndex();
        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext()).thenReturn(true, false);
        when(searchHitsIterator.next())
                .thenReturn(staleHit);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(staleContentId)
        )).thenReturn(List.of());
        doNothing().doThrow(failure).when(indexOperations).refresh();

        assertThatThrownBy(contentSearchIndexer::reindexAll)
                .isSameAs(failure);

        verify(contentSearchRepository).deleteAllById(
                List.of(staleContentId.toString())
        );
        verify(indexOperations, times(2)).refresh();
    }

    @Test
    void reindexAllSkipsCleanupWhenBulkContainsFailedItem() {
        Content content = createMockContent(
                UUID.randomUUID(),
                "Bulk 실패 콘텐츠"
        );
        stubSingleContentPage(content);
        stubExistingIndexAndConversion();

        BulkFailureException failure = new BulkFailureException(
                "Bulk update failed",
                Map.of(
                        content.getId().toString(),
                        new BulkFailureException.FailureDetails(
                                500,
                                "script failure"
                        )
                )
        );

        doThrow(failure)
                .when(elasticsearchOperations)
                .bulkUpdate(
                        anyList(),
                        any(BulkOptions.class),
                        any(IndexCoordinates.class)
                );

        assertThatThrownBy(contentSearchIndexer::reindexAll)
                .isSameAs(failure);

        verify(indexOperations, never()).refresh();
        verify(elasticsearchOperations, never())
                .searchForStream(
                        any(Query.class),
                        eq(ContentSearchDocument.class)
                );
        verify(contentSearchRepository, never())
                .deleteAllById(anyList());
    }

    @Test
    void reindexAllSkipsCleanupWhenContentPageLookupFails() {
        RuntimeException failure = new RuntimeException(
                "콘텐츠 페이지 조회 실패"
        );

        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(contentRepository.findAllByDeletedAtIsNull(
                any(Pageable.class)
        )).thenThrow(failure);

        assertThatThrownBy(contentSearchIndexer::reindexAll)
                .isSameAs(failure);

        verify(indexOperations, never()).refresh();
        verify(elasticsearchOperations, never())
                .searchForStream(
                        any(Query.class),
                        eq(ContentSearchDocument.class)
                );
        verify(contentSearchRepository, never())
                .deleteAllById(anyList());
    }

    @Test
    void reindexAllSkipsCleanupWhenStatisticsLookupFails() {
        Content content = mock(Content.class);
        UUID contentId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(
                0,
                500,
                Sort.by(Sort.Direction.ASC, "id")
        );

        when(content.getId()).thenReturn(contentId);
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(contentRepository.findAllByDeletedAtIsNull(pageable))
                .thenReturn(new PageImpl<>(
                        List.of(content),
                        pageable,
                        1
                ));
        when(contentTagRepository.findAllByContentIds(
                List.of(contentId)
        )).thenReturn(List.of());
        when(reviewRepository.findStatisticsByContentIds(
                List.of(contentId)
        )).thenThrow(new RuntimeException("리뷰 통계 조회 실패"));

        assertThatThrownBy(contentSearchIndexer::reindexAll)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("리뷰 통계 조회 실패");

        verify(elasticsearchOperations, never())
                .bulkUpdate(
                        anyList(),
                        any(BulkOptions.class),
                        any(IndexCoordinates.class)
                );
        verify(indexOperations, never()).refresh();
        verify(contentSearchRepository, never())
                .deleteAllById(anyList());
    }

    @Test
    void reindexAllSkipsCleanupWhenRefreshFails() {
        RuntimeException failure = new RuntimeException(
                "Elasticsearch refresh 실패"
        );
        stubEmptyContentPageAndExistingIndex();
        doThrow(failure).when(indexOperations).refresh();

        assertThatThrownBy(contentSearchIndexer::reindexAll)
                .isSameAs(failure);

        verify(elasticsearchOperations, never())
                .searchForStream(
                        any(Query.class),
                        eq(ContentSearchDocument.class)
                );
        verify(contentSearchRepository, never())
                .deleteAllById(anyList());
    }

    @Test
    void reindexAllClosesIdStreamAndSkipsCleanupWhenIdScanFails() {
        RuntimeException failure = new RuntimeException(
                "Elasticsearch ID 조회 실패"
        );
        stubEmptyContentPageAndExistingIndex();
        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext()).thenThrow(failure);

        assertThatThrownBy(contentSearchIndexer::reindexAll)
                .isSameAs(failure);

        verify(searchHitsIterator).close();
        verify(contentSearchRepository, never())
                .deleteAllById(anyList());
    }

    @Test
    void reindexAllSkipsEveryDeleteWhenDatabaseRecheckFails() {
        UUID firstCandidateId = UUID.randomUUID();
        UUID secondCandidateId = UUID.randomUUID();
        SearchHit<ContentSearchDocument> firstHit =
                createSearchHit(firstCandidateId);
        SearchHit<ContentSearchDocument> secondHit =
                createSearchHit(secondCandidateId);

        stubEmptyContentPageAndExistingIndex();
        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext())
                .thenReturn(true, true, false);
        when(searchHitsIterator.next())
                .thenReturn(firstHit, secondHit);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(firstCandidateId, secondCandidateId)
        )).thenThrow(new RuntimeException("활성 콘텐츠 재확인 실패"));

        assertThatThrownBy(contentSearchIndexer::reindexAll)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("활성 콘텐츠 재확인 실패");

        verify(contentSearchRepository, never())
                .deleteAllById(anyList());
        verify(searchHitsIterator).close();
    }

    @Test
    void reindexAllPropagatesElasticsearchDeleteFailure() {
        UUID staleContentId = UUID.randomUUID();
        SearchHit<ContentSearchDocument> staleHit =
                createSearchHit(staleContentId);
        RuntimeException failure = new RuntimeException(
                "Elasticsearch 문서 삭제 실패"
        );

        stubEmptyContentPageAndExistingIndex();
        when(elasticsearchOperations.searchForStream(
                any(Query.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHitsIterator);
        when(searchHitsIterator.hasNext())
                .thenReturn(true, false);
        when(searchHitsIterator.next())
                .thenReturn(staleHit);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(staleContentId)
        )).thenReturn(List.of());
        doThrow(failure).when(contentSearchRepository)
                .deleteAllById(List.of(staleContentId.toString()));

        assertThatThrownBy(contentSearchIndexer::reindexAll)
                .isSameAs(failure);

        verify(searchHitsIterator).close();
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

    private void stubExistingIndexAndConversion() {
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(elasticsearchOperations.getElasticsearchConverter())
                .thenReturn(elasticsearchConverter);
        when(elasticsearchConverter.mapObject(
                any(ContentSearchDocument.class)
        )).thenAnswer(invocation -> {
            ContentSearchDocument document = invocation.getArgument(0);
            Document source = Document.create();
            source.put("title", document.getTitle());
            source.put("embedding", List.of(0.1F, 0.2F));
            return source;
        });
    }

    private void stubEmptyContentPageAndExistingIndex() {
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(contentRepository.findAllByDeletedAtIsNull(
                any(Pageable.class)
        )).thenReturn(Page.empty());
    }

    private void stubSingleContentPage(Content content) {
        PageRequest pageable = PageRequest.of(
                0,
                500,
                Sort.by(Sort.Direction.ASC, "id")
        );

        when(contentRepository.findAllByDeletedAtIsNull(pageable))
                .thenReturn(new PageImpl<>(
                        List.of(content),
                        pageable,
                        1
                ));
        stubPageStatistics(List.of(content.getId()));
    }

    private void stubPageStatistics(List<UUID> contentIds) {
        when(contentTagRepository.findAllByContentIds(contentIds))
                .thenReturn(List.of());
        when(reviewRepository.findStatisticsByContentIds(contentIds))
                .thenReturn(List.of());
        when(contentViewRepository.findStatisticsByContentIds(contentIds))
                .thenReturn(List.of());
    }

    private Content createMockContent(UUID contentId, String title) {
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(contentId);
        when(content.getType()).thenReturn(ContentType.MOVIE);
        when(content.getTitle()).thenReturn(title);
        when(content.getExternalSource()).thenReturn(ExternalSource.MANUAL);
        return content;
    }

    private SearchHit<ContentSearchDocument> createSearchHit(
            UUID contentId
    ) {
        return createSearchHit(contentId.toString());
    }

    private SearchHit<ContentSearchDocument> createSearchHit(
            String documentId
    ) {
        @SuppressWarnings("unchecked")
        SearchHit<ContentSearchDocument> searchHit = mock(SearchHit.class);
        when(searchHit.getId()).thenReturn(documentId);
        return searchHit;
    }
}
