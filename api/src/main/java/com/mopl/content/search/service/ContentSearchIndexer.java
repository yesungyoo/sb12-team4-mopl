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
import java.util.List;
import java.util.Map;
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
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContentSearchIndexer {

    private static final int BATCH_SIZE = 500;

    private final ContentRepository contentRepository;
    private final ContentTagRepository contentTagRepository;
    private final ContentSearchRepository contentSearchRepository;
    private final ContentEmbeddingService contentEmbeddingService;
    private final ReviewRepository reviewRepository;
    private final ContentViewRepository contentViewRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    public long reindexAll() {
        recreateIndex();

        int pageNumber = 0;
        long indexedCount = 0;
        Page<Content> contentPage;

        do {
            Pageable pageable = PageRequest.of(
                    pageNumber,
                    BATCH_SIZE,
                    Sort.by(Sort.Direction.ASC, "id")
            );

            contentPage = contentRepository.findAllByDeletedAtIsNull(pageable);

            List<Content> contents = contentPage.getContent();

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
                contentSearchRepository.saveAll(documents);
                indexedCount += documents.size();
            }

            pageNumber++;
        } while (contentPage.hasNext());

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

        contentSearchRepository.save(document);
    }

    public void updateStatistics(UUID contentId) {
        contentSearchRepository.findById(contentId.toString())
                .ifPresent(document -> {
                    SearchStatistics statistics =
                            findStatistics(contentId);

                    document.updateStatistics(
                            statistics.averageRating(),
                            statistics.reviewCount(),
                            statistics.watcherCount()
                    );

                    contentSearchRepository.save(document);
                });
    }

    public void delete(UUID contentId) {
        contentSearchRepository.deleteById(contentId.toString());
    }

    private void recreateIndex() {
        IndexOperations indexOperations =
                elasticsearchOperations.indexOps(
                        ContentSearchDocument.class
                );

        if (indexOperations.exists()) {
            indexOperations.delete();
        }

        indexOperations.createWithMapping();
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
        List<Double> embedding =
                contentEmbeddingService.embedContent(
                        content,
                        contentTags
                );

        return ContentSearchDocument.from(
                content,
                contentTags,
                embedding,
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
