package com.mopl.content.search.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.exception.AiClientException;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ContentEmbeddingIndexer {

    private static final int BATCH_SIZE = 500;
    private static final int UPDATE_RETRY_ON_CONFLICT = 3;
    private static final int SEARCH_SCROLL_BATCH_SIZE = 500;

    private final ContentRepository contentRepository;
    private final ContentTagRepository contentTagRepository;
    private final ContentSearchRepository contentSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentEmbeddingService contentEmbeddingService;
    private final AiAvailability aiAvailability;

    @Value("${mopl.elasticsearch.embedding-backfill.max-consecutive-failures:5}")
    private int maxConsecutiveFailures = 5;

    public ContentEmbeddingIndexer(
            ContentRepository contentRepository,
            ContentTagRepository contentTagRepository,
            ContentSearchRepository contentSearchRepository,
            ElasticsearchOperations elasticsearchOperations,
            ContentEmbeddingService contentEmbeddingService,
            AiAvailability aiAvailability
    ) {
        this.contentRepository = contentRepository;
        this.contentTagRepository = contentTagRepository;
        this.contentSearchRepository = contentSearchRepository;
        this.elasticsearchOperations = elasticsearchOperations;
        this.contentEmbeddingService = contentEmbeddingService;
        this.aiAvailability = aiAvailability;
    }

    public void index(Content content) {
        if (!aiAvailability.isAvailable()) {
            return;
        }

        try {
            List<ContentTag> contentTags =
                    contentTagRepository.findAllByContentId(
                            content.getId()
                    );

            contentSearchRepository
                    .findById(content.getId().toString())
                    .ifPresentOrElse(
                            ignored -> embedAndUpdate(
                                    content,
                                    contentTags,
                                    RefreshPolicy.IMMEDIATE
                            ),
                            () -> log.warn(
                                    "embedding을 추가할 Elasticsearch 문서가 없습니다. contentId={}",
                                    content.getId()
                            )
                    );
        } catch (RuntimeException exception) {
            log.warn(
                    "콘텐츠 embedding 색인 준비에 실패했습니다. contentId={}",
                    content.getId(),
                    exception
            );
        }
    }

    public long reindexAll() {
        return backfillMissing().succeeded();
    }

    @PostConstruct
    void validateBackfillConfiguration() {
        if (maxConsecutiveFailures <= 0) {
            throw new IllegalStateException(
                    "mopl.elasticsearch.embedding-backfill.max-consecutive-failures 설정은 양수여야 합니다."
            );
        }
    }

    public BackfillResult backfillMissing() {
        if (!aiAvailability.isAvailable()) {
            return new BackfillResult(0L, 0L, 0L, 0L, BackfillStopReason.NONE);
        }

        BackfillCounts counts = new BackfillCounts();
        LinkedHashSet<UUID> uniqueContentIds = new LinkedHashSet<>();

        try {
            try (SearchHitsIterator<ContentSearchDocument> searchHits =
                         elasticsearchOperations.searchForStream(
                                 buildMissingEmbeddingQuery(),
                                 ContentSearchDocument.class
                         )) {
                while (searchHits.hasNext()) {
                    SearchHit<ContentSearchDocument> searchHit =
                            searchHits.next();
                    try {
                        UUID contentId = UUID.fromString(searchHit.getId());
                        if (!uniqueContentIds.add(contentId)) {
                            counts.skipped++;
                            log.warn(
                                    "embedding Backfill 검색 결과에 중복 문서 ID가 있어 건너뜁니다. contentId={}",
                                    contentId
                            );
                        }
                    } catch (IllegalArgumentException exception) {
                        counts.skipped++;
                        log.warn(
                                "embedding Backfill 검색 결과의 문서 ID가 UUID 형식이 아니어서 건너뜁니다. documentId={}",
                                searchHit.getId()
                        );
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.error(
                    "콘텐츠 Embedding Backfill 실패. 단계=ES 스트림 조회, 성공={}, 실패={}, 건너뜀={}",
                    counts.succeeded,
                    counts.failed,
                    counts.skipped,
                    exception
            );
            throw new IllegalStateException(
                    "콘텐츠 Embedding Backfill 실패. 단계=ES 스트림 조회, 성공="
                            + counts.succeeded + ", 실패=" + counts.failed
                            + ", 건너뜀=" + counts.skipped,
                    exception
            );
        }

        List<UUID> contentIds = new ArrayList<>(uniqueContentIds);
        counts.totalTargets = contentIds.size();
        try {
            for (int start = 0; start < contentIds.size(); start += BATCH_SIZE) {
                int end = Math.min(start + BATCH_SIZE, contentIds.size());
                if (processBatch(contentIds.subList(start, end), counts)) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            log.error(
                    "콘텐츠 Embedding Backfill 실패. 단계=DB 배치 처리, 성공={}, 실패={}, 건너뜀={}",
                    counts.succeeded,
                    counts.failed,
                    counts.skipped,
                    exception
            );
            throw new IllegalStateException(
                    "콘텐츠 Embedding Backfill 실패. 단계=DB 배치 처리, 성공="
                            + counts.succeeded
                            + ", 실패=" + counts.failed
                            + ", 건너뜀=" + counts.skipped,
                    exception
            );
        }

        long unprocessed = counts.totalTargets - counts.processedTargets;

        try {
            elasticsearchOperations
                    .indexOps(ContentSearchDocument.class)
                    .refresh();
        } catch (RuntimeException exception) {
            log.error(
                    "콘텐츠 Embedding Backfill 실패. 단계=ES Refresh, 성공={}, 실패={}, 건너뜀={}, 미처리={}",
                    counts.succeeded,
                    counts.failed,
                    counts.skipped,
                    unprocessed,
                    exception
            );
            throw new IllegalStateException(
                    "콘텐츠 Embedding Backfill 실패. 단계=ES Refresh, 이미 처리된 업데이트는 유지됩니다. 성공="
                            + counts.succeeded + ", 실패=" + counts.failed
                            + ", 건너뜀=" + counts.skipped
                            + ", 미처리=" + unprocessed,
                    exception
            );
        }

        if (counts.stopReason == BackfillStopReason.NONE) {
            log.info(
                    "콘텐츠 누락 embedding Backfill 완료. 성공={}, 실패={}, 건너뜀={}, 미처리={}",
                    counts.succeeded,
                    counts.failed,
                    counts.skipped,
                    unprocessed
            );
        }

        return new BackfillResult(
                counts.succeeded,
                counts.failed,
                counts.skipped,
                unprocessed,
                counts.stopReason
        );
    }

    private NativeQuery buildMissingEmbeddingQuery() {
        NativeQuery query = NativeQuery.builder()
                .withQuery(queryBuilder -> queryBuilder.bool(bool -> bool
                        .mustNot(mustNot -> mustNot.exists(exists -> exists
                                .field("embedding")
                        ))
                ))
                // Scroll 조회에서 한 번에 Elasticsearch가 반환할 문서 수입니다.
                .withPageable(PageRequest.of(0, SEARCH_SCROLL_BATCH_SIZE))
                .build();

        query.addSourceFilter(FetchSourceFilter.of(
                builder -> builder.withIncludes("id")
        ));

        return query;
    }

    private boolean processBatch(
            List<UUID> contentIds,
            BackfillCounts counts
    ) {
        if (contentIds.isEmpty()) {
            return false;
        }

        List<Content> contents =
                contentRepository.findAllByIdInAndDeletedAtIsNull(
                        contentIds
                );
        counts.skipped += contentIds.size() - contents.size();
        counts.processedTargets += contentIds.size() - contents.size();

        Map<UUID, List<ContentTag>> tagsByContentId =
                findTagsByContentId(contents);

        for (Content content : contents) {
            List<ContentTag> contentTags =
                    tagsByContentId.getOrDefault(
                            content.getId(),
                            List.of()
                    );

            EmbeddingUpdateResult result;
            try {
                result = embedAndUpdate(
                        content,
                        contentTags,
                        RefreshPolicy.NONE
                );
            } catch (RuntimeException exception) {
                Integer statusCode = findAuthenticationStatusCode(exception);
                if (statusCode == null) {
                    throw exception;
                }

                counts.failed++;
                counts.processedTargets++;
                counts.stopReason = BackfillStopReason.AUTHENTICATION_ERROR;
                log.error(
                        "콘텐츠 Embedding Backfill 조기 중단. 이유=OpenAI 인증 오류, 상태 코드={}, contentId={}, 성공={}, 실패={}, 건너뜀={}, 미처리={}, 원인={}",
                        statusCode,
                        content.getId(),
                        counts.succeeded,
                        counts.failed,
                        counts.skipped,
                        counts.totalTargets - counts.processedTargets,
                        rootCauseMessage(exception)
                );
                return true;
            }

            counts.processedTargets++;
            if (result == EmbeddingUpdateResult.SUCCEEDED) {
                counts.succeeded++;
                counts.consecutiveFailures = 0;
            } else if (result == EmbeddingUpdateResult.EMBEDDING_FAILED) {
                counts.failed++;
                counts.consecutiveFailures++;
                if (counts.consecutiveFailures >= maxConsecutiveFailures) {
                    counts.stopReason = BackfillStopReason.CONSECUTIVE_FAILURE_LIMIT;
                    log.error(
                            "콘텐츠 Embedding Backfill 조기 중단. 이유=연속 실패 임계치 도달, 임계치={}, 성공={}, 실패={}, 건너뜀={}, 미처리={}",
                            maxConsecutiveFailures,
                            counts.succeeded,
                            counts.failed,
                            counts.skipped,
                            counts.totalTargets - counts.processedTargets
                    );
                    return true;
                }
            } else {
                counts.failed++;
                // Embedding API는 성공했으므로 ES 저장 오류는 연속 API 실패 횟수에 포함하지 않습니다.
                counts.consecutiveFailures = 0;
            }
        }

        return false;
    }

    private EmbeddingUpdateResult embedAndUpdate(
            Content content,
            List<ContentTag> contentTags,
            RefreshPolicy refreshPolicy
    ) {
        List<Double> embedding;
        try {
            embedding = contentEmbeddingService.embedContent(content, contentTags);
        } catch (RuntimeException exception) {
            if (findAuthenticationStatusCode(exception) != null) {
                throw exception;
            }
            log.warn(
                    "콘텐츠 Embedding 생성 단계에 실패했습니다. contentId={}",
                    content.getId(),
                    exception
            );
            return EmbeddingUpdateResult.EMBEDDING_FAILED;
        }

        if (embedding == null || embedding.isEmpty()) {
            log.warn(
                    "콘텐츠 Embedding 생성 결과가 비어 있습니다. contentId={}",
                    content.getId()
            );
            return EmbeddingUpdateResult.EMBEDDING_FAILED;
        }

        List<Float> floatEmbedding = embedding.stream()
                .map(Double::floatValue)
                .toList();
        Document updateDocument = Document.create();
        updateDocument.put("embedding", floatEmbedding);

        UpdateQuery updateQuery = UpdateQuery.builder(content.getId().toString())
                .withDocument(updateDocument)
                .withRetryOnConflict(UPDATE_RETRY_ON_CONFLICT)
                .withRefreshPolicy(refreshPolicy)
                .build();

        try {
            elasticsearchOperations.update(
                    updateQuery,
                    elasticsearchOperations.getIndexCoordinatesFor(
                            ContentSearchDocument.class
                    )
            );
            return EmbeddingUpdateResult.SUCCEEDED;
        } catch (RuntimeException exception) {
            log.warn(
                    "콘텐츠 Embedding 저장 단계에서 Elasticsearch 업데이트에 실패했습니다. contentId={}",
                    content.getId(),
                    exception
            );
            return EmbeddingUpdateResult.ELASTICSEARCH_UPDATE_FAILED;
        }
    }

    private Integer findAuthenticationStatusCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AiClientException aiClientException) {
                Integer statusCode = aiClientException.httpStatusCode();
                if (statusCode != null
                        && (statusCode == 401 || statusCode == 403)) {
                    return statusCode;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private String rootCauseMessage(Throwable failure) {
        Throwable rootCause = failure;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getMessage() == null
                ? rootCause.getClass().getSimpleName()
                : rootCause.getMessage();
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

    private static class BackfillCounts {

        private long succeeded;
        private long failed;
        private long skipped;
        private long totalTargets;
        private long processedTargets;
        private int consecutiveFailures;
        private BackfillStopReason stopReason = BackfillStopReason.NONE;
    }

    private enum EmbeddingUpdateResult {
        SUCCEEDED,
        EMBEDDING_FAILED,
        ELASTICSEARCH_UPDATE_FAILED
    }

    public enum BackfillStopReason {
        NONE,
        AUTHENTICATION_ERROR,
        CONSECUTIVE_FAILURE_LIMIT
    }

    public record BackfillResult(
            long succeeded,
            long failed,
            long skipped,
            long unprocessed,
            BackfillStopReason stopReason
    ) {

        public boolean stoppedEarly() {
            return stopReason != BackfillStopReason.NONE;
        }
    }

}
