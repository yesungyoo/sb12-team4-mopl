package com.mopl.content.search.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.config.AiAvailability;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
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

    private final ContentRepository contentRepository;
    private final ContentTagRepository contentTagRepository;
    private final ContentSearchRepository contentSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentEmbeddingService contentEmbeddingService;
    private final AiAvailability aiAvailability;

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

    public BackfillResult backfillMissing() {
        if (!aiAvailability.isAvailable()) {
            return new BackfillResult(0L, 0L, 0L);
        }

        BackfillCounts counts = new BackfillCounts();
        List<UUID> contentIds = new ArrayList<>(BATCH_SIZE);

        try {
            try (SearchHitsIterator<ContentSearchDocument> searchHits =
                         elasticsearchOperations.searchForStream(
                                 buildMissingEmbeddingQuery(),
                                 ContentSearchDocument.class
                         )) {
                while (searchHits.hasNext()) {
                    SearchHit<ContentSearchDocument> searchHit =
                            searchHits.next();
                    contentIds.add(UUID.fromString(searchHit.getId()));

                    if (contentIds.size() == BATCH_SIZE) {
                        processBatch(contentIds, counts);
                        contentIds.clear();
                    }
                }

                processBatch(contentIds, counts);
            }
        } catch (RuntimeException exception) {
            refreshAfterFailure(exception);
            log.error(
                    "콘텐츠 embedding Backfill 조회 또는 스트림 처리가 중단되었습니다. 성공={}, 실패={}, 건너뜀={}",
                    counts.succeeded,
                    counts.failed,
                    counts.skipped,
                    exception
            );
            throw new IllegalStateException(
                    "콘텐츠 embedding Backfill을 완료하지 못했습니다.",
                    exception
            );
        }

        elasticsearchOperations
                .indexOps(ContentSearchDocument.class)
                .refresh();

        log.info(
                "콘텐츠 누락 embedding Backfill 완료. 성공={}, 실패={}, 건너뜀={}",
                counts.succeeded,
                counts.failed,
                counts.skipped
        );

        return new BackfillResult(
                counts.succeeded,
                counts.failed,
                counts.skipped
        );
    }

    private NativeQuery buildMissingEmbeddingQuery() {
        NativeQuery query = NativeQuery.builder()
                .withQuery(queryBuilder -> queryBuilder.bool(bool -> bool
                        .mustNot(mustNot -> mustNot.exists(exists -> exists
                                .field("embedding")
                        ))
                ))
                .withPageable(PageRequest.of(0, BATCH_SIZE))
                .build();

        query.addSourceFilter(FetchSourceFilter.of(
                builder -> builder.withIncludes("id")
        ));

        return query;
    }

    private void processBatch(
            List<UUID> contentIds,
            BackfillCounts counts
    ) {
        if (contentIds.isEmpty()) {
            return;
        }

        List<Content> contents =
                contentRepository.findAllByIdInAndDeletedAtIsNull(
                        contentIds
                );
        Set<UUID> activeContentIds = contents.stream()
                .map(Content::getId)
                .collect(Collectors.toCollection(HashSet::new));
        counts.skipped += contentIds.size() - activeContentIds.size();

        Map<UUID, List<ContentTag>> tagsByContentId =
                findTagsByContentId(contents);

        for (Content content : contents) {
            List<ContentTag> contentTags =
                    tagsByContentId.getOrDefault(
                            content.getId(),
                            List.of()
                    );

            if (embedAndUpdate(
                    content,
                    contentTags,
                    RefreshPolicy.NONE
            )) {
                counts.succeeded++;
            } else {
                counts.failed++;
            }
        }
    }

    private void refreshAfterFailure(RuntimeException failure) {
        try {
            elasticsearchOperations
                    .indexOps(ContentSearchDocument.class)
                    .refresh();
        } catch (RuntimeException refreshException) {
            failure.addSuppressed(refreshException);
        }
    }

    private boolean embedAndUpdate(
            Content content,
            List<ContentTag> contentTags,
            RefreshPolicy refreshPolicy
    ) {
        try {
            List<Double> embedding =
                    contentEmbeddingService.embedContent(
                            content,
                            contentTags
                    );

            if (embedding == null || embedding.isEmpty()) {
                return false;
            }

            List<Float> floatEmbedding =
                    embedding.stream()
                            .map(Double::floatValue)
                            .toList();

            Document updateDocument =
                    Document.create();

            updateDocument.put(
                    "embedding",
                    floatEmbedding
            );

            UpdateQuery updateQuery =
                    UpdateQuery.builder(
                                    content.getId().toString()
                            )
                            .withDocument(updateDocument)
                            .withRetryOnConflict(
                                    UPDATE_RETRY_ON_CONFLICT
                            )
                            .withRefreshPolicy(
                                    refreshPolicy
                            )
                            .build();

            elasticsearchOperations.update(
                    updateQuery,
                    elasticsearchOperations
                            .getIndexCoordinatesFor(
                                    ContentSearchDocument.class
                            )
            );

            return true;
        } catch (RuntimeException exception) {
            log.warn(
                    "콘텐츠 embedding 생성 또는 갱신에 실패했습니다. contentId={}",
                    content.getId(),
                    exception
            );

            return false;
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

    private static class BackfillCounts {

        private long succeeded;
        private long failed;
        private long skipped;
    }

    public record BackfillResult(
            long succeeded,
            long failed,
            long skipped
    ) {
    }

}
