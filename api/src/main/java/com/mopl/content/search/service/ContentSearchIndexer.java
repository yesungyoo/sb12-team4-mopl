package com.mopl.content.search.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.repository.ContentViewRepository;
import com.mopl.content.repository.projection.ContentViewStatisticsProjection;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.review.repository.ReviewRepository;
import com.mopl.review.repository.projection.ContentReviewStatisticsProjection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.BulkOptions;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContentSearchIndexer {

    private static final int BATCH_SIZE = 500;
    private static final int UPDATE_RETRY_ON_CONFLICT = 3;
    private static final String UPSERT_PRESERVING_EMBEDDING_SCRIPT = """
            def embedding = ctx._source.get('embedding');
            ctx._source.clear();
            ctx._source.putAll(params.document);
            if (embedding != null) {
                ctx._source.put('embedding', embedding);
            }
            """;

    private final ContentRepository contentRepository;
    private final ContentTagRepository contentTagRepository;
    private final ContentSearchRepository contentSearchRepository;
    private final ReviewRepository reviewRepository;
    private final ContentViewRepository contentViewRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    public long reindexAll() {
        IndexOperations indexOperations = ensureIndexExists();
        IndexCoordinates indexCoordinates = elasticsearchOperations
                .getIndexCoordinatesFor(ContentSearchDocument.class);

        int pageNumber = 0;
        long indexedCount = 0;
        Page<Content> contentPage;
        Set<String> activeContentIds = new LinkedHashSet<>();

        do {
            Pageable pageable = PageRequest.of(
                    pageNumber,
                    BATCH_SIZE,
                    Sort.by(Sort.Direction.ASC, "id")
            );

            contentPage = contentRepository.findAllByDeletedAtIsNull(pageable);

            List<Content> contents = contentPage.getContent();

            contents.stream()
                    .map(Content::getId)
                    .map(UUID::toString)
                    .forEach(activeContentIds::add);

            Map<UUID, List<ContentTag>> tagsByContentId =
                    findTagsByContentId(contents);

            Map<UUID, ContentReviewStatisticsProjection> reviewStatisticsByContentId =
                    findReviewStatisticsByContentId(contents);

            Map<UUID, Long> watcherCountByContentId =
                    findWatcherCountByContentId(contents);

            List<ContentSearchDocument> documents = contents.stream()
                    .map(content -> createDocument(
                            content,
                            tagsByContentId.getOrDefault(
                                    content.getId(),
                                    List.of()
                            ),
                            createStatistics(
                                    content.getId(),
                                    reviewStatisticsByContentId,
                                    watcherCountByContentId
                            )
                    ))
                    .toList();

            if (!documents.isEmpty()) {
                bulkUpsertPreservingEmbedding(
                        documents,
                        indexCoordinates
                );
                indexedCount += documents.size();
            }

            pageNumber++;
        } while (contentPage.hasNext());

        indexOperations.refresh();

        Set<String> staleDocumentIds = findStaleDocumentIds(
                activeContentIds
        );

        if (deleteStaleDocumentsAfterRecheck(staleDocumentIds)) {
            indexOperations.refresh();
        }

        return indexedCount;
    }

    public void index(Content content) {
        List<ContentTag> contentTags =
                contentTagRepository.findAllByContentId(content.getId());

        SearchStatistics statistics =
                findStatistics(content.getId());

        ContentSearchDocument document = createDocument(
                content,
                contentTags,
                statistics
        );

        IndexCoordinates indexCoordinates = elasticsearchOperations
                .getIndexCoordinatesFor(ContentSearchDocument.class);

        UpdateQuery updateQuery = createUpsertPreservingEmbeddingQuery(
                document,
                RefreshPolicy.IMMEDIATE
        );

        elasticsearchOperations.update(
                updateQuery,
                indexCoordinates
        );
    }

    public void updateStatistics(UUID contentId) {
        contentSearchRepository.findById(contentId.toString())
                .ifPresent(ignored -> {
                    SearchStatistics statistics =
                            findStatistics(contentId);

                    Document updateDocument =
                            Document.create();

                    updateDocument.put(
                            "averageRating",
                            statistics.averageRating() == null
                                    ? 0.0D
                                    : statistics.averageRating()
                    );
                    updateDocument.put(
                            "reviewCount",
                            statistics.reviewCount()
                    );
                    updateDocument.put(
                            "watcherCount",
                            statistics.watcherCount()
                    );

                    UpdateQuery updateQuery =
                            UpdateQuery.builder(contentId.toString())
                                    .withDocument(updateDocument)
                                    .withRetryOnConflict(
                                            UPDATE_RETRY_ON_CONFLICT
                                    )
                                    .withRefreshPolicy(
                                            RefreshPolicy.IMMEDIATE
                                    )
                                    .build();

                    elasticsearchOperations.update(
                            updateQuery,
                            elasticsearchOperations
                                    .getIndexCoordinatesFor(
                                            ContentSearchDocument.class
                                    )
                    );
                });
    }

    public void delete(UUID contentId) {
        contentSearchRepository.deleteById(contentId.toString());
    }

    private UpdateQuery createUpsertPreservingEmbeddingQuery(
            ContentSearchDocument document,
            RefreshPolicy refreshPolicy
    ) {
        Document source =
                elasticsearchOperations
                        .getElasticsearchConverter()
                        .mapObject(document);

        source.remove("embedding");

        return UpdateQuery.builder(document.getId())
                .withScriptType(
                        ScriptType.INLINE
                )
                .withScript(
                        UPSERT_PRESERVING_EMBEDDING_SCRIPT
                )
                .withParams(
                        Map.of("document", source)
                )
                .withUpsert(source)
                .withRetryOnConflict(
                        UPDATE_RETRY_ON_CONFLICT
                )
                .withRefreshPolicy(
                        refreshPolicy
                )
                .build();
    }

    private void bulkUpsertPreservingEmbedding(
            List<ContentSearchDocument> documents,
            IndexCoordinates indexCoordinates
    ) {
        List<UpdateQuery> updateQueries = documents.stream()
                .map(document -> createUpsertPreservingEmbeddingQuery(
                        document,
                        RefreshPolicy.NONE
                ))
                .toList();

        BulkOptions bulkOptions = BulkOptions.builder()
                .withRefreshPolicy(RefreshPolicy.NONE)
                .build();

        elasticsearchOperations.bulkUpdate(
                updateQueries,
                bulkOptions,
                indexCoordinates
        );
    }

    private IndexOperations ensureIndexExists() {
        IndexOperations indexOperations =
                elasticsearchOperations.indexOps(
                        ContentSearchDocument.class
                );

        if (!indexOperations.exists()
                && !indexOperations.createWithMapping()) {
            throw new IllegalStateException(
                    "Elasticsearch 콘텐츠 인덱스를 생성하지 못했습니다."
            );
        }

        return indexOperations;
    }

    private Set<String> findStaleDocumentIds(
            Set<String> activeContentIds
    ) {
        Query query = Query.findAll();
        query.setPageable(PageRequest.of(0, BATCH_SIZE));
        query.addSourceFilter(new FetchSourceFilter(
                null,
                new String[]{"id"},
                null
        ));

        Set<String> staleDocumentIds = new LinkedHashSet<>();

        try (SearchHitsIterator<ContentSearchDocument> searchHits =
                     elasticsearchOperations.searchForStream(
                             query,
                             ContentSearchDocument.class
                     )) {
            while (searchHits.hasNext()) {
                String documentId = searchHits.next().getId();

                if (!activeContentIds.contains(documentId)) {
                    staleDocumentIds.add(documentId);
                }
            }
        }

        return staleDocumentIds;
    }

    private boolean deleteStaleDocumentsAfterRecheck(
            Set<String> staleDocumentIds
    ) {
        if (staleDocumentIds.isEmpty()) {
            return false;
        }

        List<String> candidates = List.copyOf(staleDocumentIds);
        Map<String, UUID> candidateIds = candidates.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        this::parseDocumentId,
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
        List<String> deletableDocumentIds = new ArrayList<>();

        for (int start = 0; start < candidates.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, candidates.size());
            List<String> candidateBatch = candidates.subList(start, end);

            List<UUID> candidateContentIds = candidateBatch.stream()
                    .map(candidateIds::get)
                    .toList();

            Set<UUID> activeCandidateIds = contentRepository
                    .findAllByIdInAndDeletedAtIsNull(candidateContentIds)
                    .stream()
                    .map(Content::getId)
                    .collect(Collectors.toSet());

            candidateBatch.stream()
                    .filter(documentId -> !activeCandidateIds.contains(
                            candidateIds.get(documentId)
                    ))
                    .forEach(deletableDocumentIds::add);
        }

        for (int start = 0; start < deletableDocumentIds.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, deletableDocumentIds.size());
            contentSearchRepository.deleteAllById(
                    deletableDocumentIds.subList(start, end)
            );
        }

        return !deletableDocumentIds.isEmpty();
    }

    private UUID parseDocumentId(String documentId) {
        try {
            UUID parsedId = UUID.fromString(documentId);
            if (!parsedId.toString().equals(documentId)) {
                throw new IllegalArgumentException();
            }
            return parsedId;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException(
                    "Elasticsearch 문서 ID가 유효한 UUID 형식이 아닙니다. documentId="
                            + documentId,
                    exception
            );
        }
    }

    private Map<UUID, List<ContentTag>> findTagsByContentId(
            List<Content> contents
    ) {
        if (contents.isEmpty()) {
            return Map.of();
        }

        List<UUID> contentIds = contents.stream()
                .map(Content::getId)
                .toList();

        return contentTagRepository
                .findAllByContentIds(contentIds)
                .stream()
                .collect(Collectors.groupingBy(contentTag ->
                        contentTag.getContent().getId()));
    }

    private Map<UUID, ContentReviewStatisticsProjection> findReviewStatisticsByContentId(
            List<Content> contents
    ) {
        if (contents.isEmpty()) {
            return Map.of();
        }

        List<UUID> contentIds = contents.stream()
                .map(Content::getId)
                .toList();

        return reviewRepository
                .findStatisticsByContentIds(contentIds)
                .stream()
                .collect(Collectors.toMap(
                        ContentReviewStatisticsProjection::getContentId,
                        Function.identity()
                ));
    }

    private Map<UUID, Long> findWatcherCountByContentId(
            List<Content> contents
    ) {
        if (contents.isEmpty()) {
            return Map.of();
        }

        List<UUID> contentIds = contents.stream()
                .map(Content::getId)
                .toList();

        return contentViewRepository
                .findStatisticsByContentIds(contentIds)
                .stream()
                .collect(Collectors.toMap(
                        ContentViewStatisticsProjection::getContentId,
                        ContentViewStatisticsProjection::getWatcherCount
                ));
    }

    private SearchStatistics createStatistics(
            UUID contentId,
            Map<UUID, ContentReviewStatisticsProjection> reviewStatisticsByContentId,
            Map<UUID, Long> watcherCountByContentId
    ) {
        ContentReviewStatisticsProjection reviewStatistics =
                reviewStatisticsByContentId.get(contentId);

        Double averageRating = reviewStatistics == null
                ? null
                : reviewStatistics.getAverageRating();

        long reviewCount = reviewStatistics == null
                ? 0L
                : reviewStatistics.getReviewCount();

        long watcherCount =
                watcherCountByContentId.getOrDefault(
                        contentId,
                        0L
                );

        return new SearchStatistics(
                averageRating,
                reviewCount,
                watcherCount
        );
    }

    private SearchStatistics findStatistics(UUID contentId) {
        Double averageRating =
                reviewRepository.findAverageRatingByContentId(contentId);

        long reviewCount =
                reviewRepository.countByContentId(contentId);

        long watcherCount =
                contentViewRepository.countByContent_Id(contentId);

        return new SearchStatistics(
                averageRating,
                reviewCount,
                watcherCount
        );
    }

    private ContentSearchDocument createDocument(
            Content content,
            List<ContentTag> contentTags,
            SearchStatistics statistics
    ) {
        return ContentSearchDocument.from(
                content,
                contentTags,
                statistics.averageRating(),
                statistics.reviewCount(),
                statistics.watcherCount()
        );
    }

    private record SearchStatistics(
            Double averageRating,
            long reviewCount,
            long watcherCount
    ) {
    }
}
