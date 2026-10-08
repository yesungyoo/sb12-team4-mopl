package com.mopl.content.search.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.config.AiAvailability;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.document.Document;
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
        if (!aiAvailability.isAvailable()) {
            return 0L;
        }

        int pageNumber = 0;
        long indexedCount = 0L;
        Page<Content> contentPage;

        try {
            do {
                Pageable pageable = PageRequest.of(
                        pageNumber,
                        BATCH_SIZE,
                        Sort.by(Sort.Direction.ASC, "id")
                );

                contentPage =
                        contentRepository.findAllByDeletedAtIsNull(
                                pageable
                        );

                List<Content> contents =
                        contentPage.getContent();

                Map<UUID, List<ContentTag>> tagsByContentId =
                        findTagsByContentId(contents);

                Map<UUID, ContentSearchDocument> documentsByContentId =
                        findDocumentsByContentId(contents);

                for (Content content : contents) {
                    ContentSearchDocument document =
                            documentsByContentId.get(
                                    content.getId()
                            );

                    if (document == null) {
                        log.warn(
                                "embedding을 추가할 Elasticsearch 문서가 없습니다. contentId={}",
                                content.getId()
                        );
                        continue;
                    }

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
                        indexedCount++;
                    }
                }

                pageNumber++;
            } while (contentPage.hasNext());
        } catch (RuntimeException exception) {
            log.warn(
                    "콘텐츠 embedding 전체 색인에 실패했습니다. indexedCount={}",
                    indexedCount,
                    exception
            );
        }

        try {
            elasticsearchOperations
                    .indexOps(
                            ContentSearchDocument.class
                    )
                    .refresh();
        } catch (RuntimeException exception) {
            log.warn(
                    "콘텐츠 embedding 전체 색인 후 Elasticsearch refresh에 실패했습니다. indexedCount={}",
                    indexedCount,
                    exception
            );
        }

        return indexedCount;
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

    private Map<UUID, ContentSearchDocument> findDocumentsByContentId(
            List<Content> contents
    ) {
        if (contents.isEmpty()) {
            return Map.of();
        }

        List<String> documentIds = contents.stream()
                .map(Content::getId)
                .map(UUID::toString)
                .toList();

        Iterable<ContentSearchDocument> documents =
                contentSearchRepository.findAllById(
                        documentIds
                );

        return StreamSupport.stream(
                        documents.spliterator(),
                        false
                )
                .collect(Collectors.toMap(
                        document ->
                                UUID.fromString(
                                        document.getId()
                                ),
                        Function.identity()
                ));
    }

}
