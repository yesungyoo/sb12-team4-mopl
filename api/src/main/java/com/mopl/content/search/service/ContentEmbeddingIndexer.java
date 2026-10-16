package com.mopl.content.search.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.query.ContentSearchSourceFilters;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.config.AiProperties;
import com.mopl.infrastructure.ai.exception.AiClientException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.ResourceNotFoundException;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.MultiGetItem;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.UpdateResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class ContentEmbeddingIndexer {

    private static final int BATCH_SIZE = 500;
    private static final int UPDATE_RETRY_ON_CONFLICT = 3;
    private static final int SEARCH_SCROLL_BATCH_SIZE = 500;
    private static final String UPDATE_EMBEDDING_IF_INPUT_CURRENT_SCRIPT = """
            if (ctx._source.containsKey('contentInputHash')
                    && ctx._source.contentInputHash != null
                    && ctx._source.contentInputHash == params.embeddingInputHash) {
                ctx._source.embedding = params.embedding;
                ctx._source.embeddingInputHash = params.embeddingInputHash;
                ctx._source.embeddingModel = params.embeddingModel;
            } else {
                ctx.op = 'noop';
            }
            """;

    private final ContentRepository contentRepository;
    private final ContentTagRepository contentTagRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentEmbeddingService contentEmbeddingService;
    private final AiProperties aiProperties;
    private final AiAvailability aiAvailability;

    @Value("${mopl.elasticsearch.embedding-backfill.max-consecutive-failures:5}")
    private int maxConsecutiveFailures = 5;

    @Value("${mopl.elasticsearch.embedding-backfill.max-consecutive-es-failures:5}")
    private int maxConsecutiveElasticsearchFailures = 5;

    public ContentEmbeddingIndexer(
            ContentRepository contentRepository,
            ContentTagRepository contentTagRepository,
            ElasticsearchOperations elasticsearchOperations,
            ContentEmbeddingService contentEmbeddingService,
            AiProperties aiProperties,
            AiAvailability aiAvailability
    ) {
        this.contentRepository = contentRepository;
        this.contentTagRepository = contentTagRepository;
        this.elasticsearchOperations = elasticsearchOperations;
        this.contentEmbeddingService = contentEmbeddingService;
        this.aiProperties = aiProperties;
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

            ContentSearchDocument document =
                    getDocumentMetadata(content.getId());

            if (document == null) {
                log.warn(
                        "embedding을 추가할 Elasticsearch 문서가 없습니다. contentId={}",
                        content.getId()
                );
                return;
            }

            indexExistingDocument(
                    content,
                    contentTags,
                    document
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "콘텐츠 embedding 색인 준비에 실패했습니다. contentId={}",
                    content.getId(),
                    exception
            );
        }
    }

    private void indexExistingDocument(
            Content content,
            List<ContentTag> contentTags,
            ContentSearchDocument document
    ) {
        String currentInputHash =
                contentEmbeddingService.calculateInputHash(
                        content,
                        contentTags
                );
        String indexedInputHash = document.getContentInputHash();

        if (!Objects.equals(
                indexedInputHash,
                currentInputHash
        )) {
            log.warn(
                    "일반 색인의 contentInputHash가 현재 DB 입력과 일치하지 않아 embedding 재계산을 건너뜁니다. contentId={}, contentInputHash={}, currentInputHash={}",
                    content.getId(),
                    indexedInputHash,
                    currentInputHash
            );
            return;
        }

        boolean requiresMetadataRecalculation =
                requiresEmbeddingMetadataRecalculation(
                        document,
                        currentInputHash,
                        aiProperties.embeddingModel()
                );

        if (!requiresMetadataRecalculation
                && hasEmbedding(content.getId())) {
            return;
        }

        embedAndUpdate(
                content,
                contentTags,
                RefreshPolicy.IMMEDIATE
        );
    }

    private boolean requiresEmbeddingMetadataRecalculation(
            ContentSearchDocument document,
            String currentInputHash,
            String currentModel
    ) {
        return !Objects.equals(
                        document.getEmbeddingInputHash(),
                        currentInputHash
                )
                || !Objects.equals(
                        document.getEmbeddingModel(),
                        currentModel
                );
    }

    public long reindexAll() {
        return backfillMissing().succeeded();
    }

    public void validateBackfillConfiguration() {
        if (maxConsecutiveFailures <= 0) {
            throw new BackfillConfigurationException(
                    "mopl.elasticsearch.embedding-backfill.max-consecutive-failures 설정은 양수여야 합니다."
            );
        }
        if (maxConsecutiveElasticsearchFailures <= 0) {
            throw new BackfillConfigurationException(
                    "mopl.elasticsearch.embedding-backfill.max-consecutive-es-failures 설정은 양수여야 합니다."
            );
        }
    }

    public BackfillResult backfillMissing() {
        validateBackfillConfiguration();
        if (!aiAvailability.isAvailable()) {
            return new BackfillResult(0L, 0L, 0L, 0L, BackfillStopReason.NONE);
        }

        BackfillCounts counts = new BackfillCounts();
        LinkedHashSet<UUID> candidateContentIds = new LinkedHashSet<>();
        LinkedHashSet<UUID> currentMetadataContentIds = new LinkedHashSet<>();
        LinkedHashSet<UUID> seenContentIds = new LinkedHashSet<>();
        String currentModel = aiProperties.embeddingModel();

        try {
            try (SearchHitsIterator<ContentSearchDocument> searchHits =
                         elasticsearchOperations.searchForStream(
                                 buildEmbeddingMetadataScanQuery(),
                                 ContentSearchDocument.class
                         )) {
                while (searchHits.hasNext()) {
                    SearchHit<ContentSearchDocument> searchHit =
                            searchHits.next();
                    try {
                        UUID contentId = UUID.fromString(searchHit.getId());
                        if (!seenContentIds.add(contentId)) {
                            counts.skipped++;
                            log.warn(
                                    "embedding Backfill 검색 결과에 중복 문서 ID가 있어 건너뜁니다. contentId={}",
                                    contentId
                            );
                            continue;
                        }

                        ContentSearchDocument document = searchHit.getContent();
                        String indexedInputHash = document.getContentInputHash();
                        if (!StringUtils.hasText(indexedInputHash)
                                || requiresEmbeddingMetadataRecalculation(
                                        document,
                                        indexedInputHash,
                                        currentModel
                                )) {
                            candidateContentIds.add(contentId);
                        } else {
                            currentMetadataContentIds.add(contentId);
                            if (currentMetadataContentIds.size() == BATCH_SIZE) {
                                collectMissingEmbeddingIds(
                                        new ArrayList<>(currentMetadataContentIds),
                                        candidateContentIds,
                                        counts
                                );
                                currentMetadataContentIds.clear();
                            }
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
            collectMissingEmbeddingIds(
                    new ArrayList<>(currentMetadataContentIds),
                    candidateContentIds,
                    counts
            );
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

        List<UUID> contentIds = new ArrayList<>(candidateContentIds);
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

    private NativeQuery buildEmbeddingMetadataScanQuery() {
        return NativeQuery.builder()
                .withQuery(queryBuilder -> queryBuilder.matchAll(matchAll -> matchAll))
                // Scroll 조회에서 한 번에 Elasticsearch가 반환할 문서 수입니다.
                .withPageable(PageRequest.of(0, SEARCH_SCROLL_BATCH_SIZE))
                .withSourceFilter(
                        ContentSearchSourceFilters.embeddingMetadataOnly()
                )
                .build();
    }

    private NativeQuery buildMissingEmbeddingByIdsQuery(
            List<UUID> contentIds
    ) {
        return NativeQuery.builder()
                .withQuery(queryBuilder -> queryBuilder.bool(bool -> bool
                        .must(must -> must.ids(ids -> ids.values(
                                contentIds.stream()
                                        .map(UUID::toString)
                                        .toList()
                        )))
                        .mustNot(mustNot -> mustNot.exists(exists -> exists
                                .field(ContentSearchDocument.EMBEDDING_FIELD)
                        ))
                ))
                .withPageable(PageRequest.of(0, SEARCH_SCROLL_BATCH_SIZE))
                .withSourceFilter(ContentSearchSourceFilters.idOnly())
                .build();
    }

    private NativeQuery buildDocumentEmbeddingExistsQuery(UUID contentId) {
        return NativeQuery.builder()
                .withQuery(queryBuilder -> queryBuilder.bool(bool -> bool
                        .must(must -> must.ids(ids -> ids.values(
                                List.of(contentId.toString())
                        )))
                        .must(must -> must.exists(exists -> exists
                                .field(ContentSearchDocument.EMBEDDING_FIELD)
                        ))
                ))
                .withSourceFilter(ContentSearchSourceFilters.idOnly())
                .build();
    }

    private ContentSearchDocument getDocumentMetadata(UUID contentId) {
        Query query = Query.multiGetQuery(List.of(contentId.toString()));
        query.addSourceFilter(
                ContentSearchSourceFilters.embeddingMetadataOnly()
        );

        List<MultiGetItem<ContentSearchDocument>> items;
        try {
            items = elasticsearchOperations.multiGet(
                    query,
                    ContentSearchDocument.class,
                    elasticsearchOperations.getIndexCoordinatesFor(
                            ContentSearchDocument.class
                    )
            );
        } catch (ResourceNotFoundException exception) {
            // mget의 인덱스 부재는 아래 isFailed() 분기에서 처리합니다.
            // 요청 자체가 예외를 던지는 경우에도 ES 실패로 처리하기 위한 방어 코드입니다.
            throw new IllegalStateException(
                    "Elasticsearch embedding 메타데이터 조회에 실패했습니다. contentId="
                            + contentId,
                    exception
            );
        }

        if (items.isEmpty()) {
            return null;
        }

        MultiGetItem<ContentSearchDocument> item = items.getFirst();
        // 문서 부재는 getItem() == null, 인덱스 부재는 실패 항목으로 반환됩니다.
        if (item.isFailed()) {
            MultiGetItem.Failure failure = item.getFailure();
            throw new IllegalStateException(
                    "Elasticsearch embedding 메타데이터 조회에 실패했습니다. contentId="
                            + contentId,
                    failure == null ? null : failure.getException()
            );
        }

        return item.getItem();
    }

    private void collectMissingEmbeddingIds(
            List<UUID> contentIds,
            LinkedHashSet<UUID> candidateContentIds,
            BackfillCounts counts
    ) {
        if (contentIds.isEmpty()) {
            return;
        }

        LinkedHashSet<String> seenDocumentIds = new LinkedHashSet<>();
        try (SearchHitsIterator<ContentSearchDocument> searchHits =
                     elasticsearchOperations.searchForStream(
                             buildMissingEmbeddingByIdsQuery(contentIds),
                             ContentSearchDocument.class
                     )) {
            while (searchHits.hasNext()) {
                SearchHit<ContentSearchDocument> searchHit = searchHits.next();
                String documentId = searchHit.getId();
                try {
                    UUID contentId = UUID.fromString(documentId);
                    if (!seenDocumentIds.add(documentId)) {
                        log.warn(
                                "embedding Backfill 검색 결과에 중복 문서 ID가 있어 건너뜁니다. contentId={}",
                                contentId
                        );
                        continue;
                    }
                    candidateContentIds.add(contentId);
                } catch (IllegalArgumentException exception) {
                    counts.skipped++;
                    log.warn(
                            "embedding Backfill 검색 결과의 문서 ID가 UUID 형식이 아니어서 건너뜁니다. documentId={}",
                            documentId
                    );
                }
            }
        }
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
                result = embedIfNeeded(
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
                counts.consecutiveElasticsearchFailures = 0;
            } else if (result == EmbeddingUpdateResult.STALE_REJECTED
                    || result == EmbeddingUpdateResult.DOCUMENT_NOT_FOUND
                    || result == EmbeddingUpdateResult.ALREADY_CURRENT) {
                counts.skipped++;
                counts.consecutiveFailures = 0;
                counts.consecutiveElasticsearchFailures = 0;
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
                counts.consecutiveElasticsearchFailures++;
                if (counts.consecutiveElasticsearchFailures
                        >= maxConsecutiveElasticsearchFailures) {
                    counts.stopReason = BackfillStopReason
                            .CONSECUTIVE_ELASTICSEARCH_FAILURE_LIMIT;
                    log.error(
                            "콘텐츠 Embedding Backfill 조기 중단. 이유=Elasticsearch 연속 저장 실패 임계치 도달, 임계치={}, 성공={}, 실패={}, 건너뜀={}, 미처리={}",
                            maxConsecutiveElasticsearchFailures,
                            counts.succeeded,
                            counts.failed,
                            counts.skipped,
                            counts.totalTargets - counts.processedTargets
                    );
                    return true;
                }
            }
        }

        return false;
    }

    private EmbeddingUpdateResult embedIfNeeded(
            Content content,
            List<ContentTag> contentTags,
            RefreshPolicy refreshPolicy
    ) {
        UUID contentId = content.getId();
        String currentInputHash;
        ContentSearchDocument document;
        try {
            currentInputHash = contentEmbeddingService.calculateInputHash(
                    content,
                    contentTags
            );
            document = getDocumentMetadata(contentId);
        } catch (RuntimeException exception) {
            log.warn(
                    "embedding Backfill 최신 상태 확인에 실패했습니다. contentId={}",
                    contentId,
                    exception
            );
            return EmbeddingUpdateResult.ELASTICSEARCH_UPDATE_FAILED;
        }

        if (document == null) {
            log.warn(
                    "embedding Backfill 처리 전에 Elasticsearch 문서가 삭제되어 건너뜁니다. contentId={}",
                    contentId
            );
            return EmbeddingUpdateResult.DOCUMENT_NOT_FOUND;
        }

        String indexedInputHash = document.getContentInputHash();
        if (!StringUtils.hasText(indexedInputHash)) {
            log.warn(
                    "contentInputHash가 없어 일반 SYNC_DATA가 필요하므로 embedding Backfill을 건너뜁니다. contentId={}",
                    contentId
            );
            return EmbeddingUpdateResult.STALE_REJECTED;
        }
        if (!Objects.equals(indexedInputHash, currentInputHash)) {
            log.warn(
                    "Elasticsearch contentInputHash가 현재 DB 입력과 달라 embedding Backfill을 건너뜁니다. contentId={}, contentInputHash={}, currentInputHash={}",
                    contentId,
                    indexedInputHash,
                    currentInputHash
            );
            return EmbeddingUpdateResult.STALE_REJECTED;
        }

        String currentModel = aiProperties.embeddingModel();
        boolean hasEmbedding;
        try {
            hasEmbedding = !requiresEmbeddingMetadataRecalculation(
                    document,
                    currentInputHash,
                    currentModel
            ) && hasEmbedding(contentId);
        } catch (RuntimeException exception) {
            log.warn(
                    "embedding Backfill 최신 벡터 상태 확인에 실패했습니다. contentId={}",
                    contentId,
                    exception
            );
            return EmbeddingUpdateResult.ELASTICSEARCH_UPDATE_FAILED;
        }
        if (hasEmbedding) {
            log.debug(
                    "embedding이 이미 최신 상태여서 Backfill을 건너뜁니다. contentId={}",
                    contentId
            );
            return EmbeddingUpdateResult.ALREADY_CURRENT;
        }

        return embedAndUpdate(content, contentTags, refreshPolicy);
    }

    private boolean hasEmbedding(UUID contentId) {
        // exists 쿼리는 near-realtime 검색입니다. 일반 동기화 경로는
        // ContentSearchIndexer의 IMMEDIATE 갱신 후 이 메서드를 호출합니다.
        return elasticsearchOperations.searchOne(
                buildDocumentEmbeddingExistsQuery(contentId),
                ContentSearchDocument.class,
                elasticsearchOperations.getIndexCoordinatesFor(
                        ContentSearchDocument.class
                )
        ) != null;
    }

    private EmbeddingUpdateResult embedAndUpdate(
            Content content,
            List<ContentTag> contentTags,
            RefreshPolicy refreshPolicy
    ) {
        ContentEmbeddingService.EmbeddingGeneration generation;
        try {
            generation = contentEmbeddingService.embedContentWithMetadata(
                    content,
                    contentTags
            );
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

        if (generation == null
                || generation.embedding() == null
                || generation.embedding().isEmpty()
                || !StringUtils.hasText(generation.inputHash())
                || !StringUtils.hasText(generation.model())) {
            log.warn(
                    "콘텐츠 Embedding 생성 결과 또는 메타데이터가 비어 있습니다. contentId={}",
                    content.getId()
            );
            return EmbeddingUpdateResult.EMBEDDING_FAILED;
        }

        List<Float> floatEmbedding = generation.embedding().stream()
                .map(Double::floatValue)
                .toList();

        UpdateQuery updateQuery = UpdateQuery.builder(content.getId().toString())
                .withScriptType(ScriptType.INLINE)
                .withScript(UPDATE_EMBEDDING_IF_INPUT_CURRENT_SCRIPT)
                .withParams(Map.of(
                        "embedding",
                        floatEmbedding,
                        "embeddingInputHash",
                        generation.inputHash(),
                        "embeddingModel",
                        generation.model()
                ))
                .withRetryOnConflict(UPDATE_RETRY_ON_CONFLICT)
                .withRefreshPolicy(refreshPolicy)
                .build();

        try {
            UpdateResponse updateResponse = elasticsearchOperations.update(
                    updateQuery,
                    elasticsearchOperations.getIndexCoordinatesFor(
                            ContentSearchDocument.class
                    )
            );
            return resolveUpdateResult(
                    content.getId(),
                    updateResponse
            );
        } catch (ResourceNotFoundException exception) {
            log.warn(
                    "콘텐츠 Embedding 저장 전에 Elasticsearch 문서가 삭제되어 건너뜁니다. contentId={}",
                    content.getId()
            );
            return EmbeddingUpdateResult.DOCUMENT_NOT_FOUND;
        } catch (RuntimeException exception) {
            log.warn(
                    "콘텐츠 Embedding 저장 단계에서 Elasticsearch 업데이트에 실패했습니다. contentId={}",
                    content.getId(),
                    exception
            );
            return EmbeddingUpdateResult.ELASTICSEARCH_UPDATE_FAILED;
        }
    }

    private EmbeddingUpdateResult resolveUpdateResult(
            UUID contentId,
            UpdateResponse updateResponse
    ) {
        if (updateResponse == null) {
            log.warn(
                    "콘텐츠 Embedding 저장 결과가 비어 있습니다. contentId={}",
                    contentId
            );
            return EmbeddingUpdateResult.ELASTICSEARCH_UPDATE_FAILED;
        }

        UpdateResponse.Result result = updateResponse.getResult();
        if (result == null) {
            log.warn(
                    "콘텐츠 Embedding 저장 결과 유형이 비어 있습니다. contentId={}",
                    contentId
            );
            return EmbeddingUpdateResult.ELASTICSEARCH_UPDATE_FAILED;
        }

        return switch (result) {
            case UPDATED -> EmbeddingUpdateResult.SUCCEEDED;
            case NOOP -> {
                log.warn(
                        "최신 contentInputHash와 생성 당시 hash가 일치하지 않아 stale embedding 업데이트를 거부했습니다. contentId={}",
                        contentId
                );
                yield EmbeddingUpdateResult.STALE_REJECTED;
            }
            case NOT_FOUND -> {
                log.warn(
                        "콘텐츠 Embedding 저장 전에 Elasticsearch 문서가 삭제되어 건너뜁니다. contentId={}",
                        contentId
                );
                yield EmbeddingUpdateResult.DOCUMENT_NOT_FOUND;
            }
            default -> {
                log.warn(
                        "예상하지 못한 콘텐츠 Embedding 저장 결과입니다. contentId={}, result={}",
                        contentId,
                        result
                );
                yield EmbeddingUpdateResult.ELASTICSEARCH_UPDATE_FAILED;
            }
        };
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
        private int consecutiveElasticsearchFailures;
        private BackfillStopReason stopReason = BackfillStopReason.NONE;
    }

    private enum EmbeddingUpdateResult {
        SUCCEEDED,
        STALE_REJECTED,
        DOCUMENT_NOT_FOUND,
        ALREADY_CURRENT,
        EMBEDDING_FAILED,
        ELASTICSEARCH_UPDATE_FAILED
    }

    public enum BackfillStopReason {
        NONE,
        AUTHENTICATION_ERROR,
        CONSECUTIVE_FAILURE_LIMIT,
        CONSECUTIVE_ELASTICSEARCH_FAILURE_LIMIT
    }

    public static class BackfillConfigurationException extends IllegalStateException {

        public BackfillConfigurationException(String message) {
            super(message);
        }
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
