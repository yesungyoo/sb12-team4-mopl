package com.mopl.recommendation.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.repository.ContentViewRepository;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.core.domain.content.entity.ContentView;
import com.mopl.core.domain.review.entity.Review;
import com.mopl.recommendation.dto.RecommendationPreference;
import com.mopl.recommendation.dto.RecommendationPreferredTag;
import com.mopl.review.repository.ReviewRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationPreferenceService {

    private static final int RECENT_VIEW_LIMIT = 20;
    private static final int HIGH_RATED_REVIEW_LIMIT = 20;

    private static final BigDecimal HIGH_RATING_THRESHOLD = new BigDecimal("4.0");

    private static final int MAX_FREQUENT_VIEW_TITLES = 5;
    private static final int MAX_HIGH_RATED_TITLES = 5;

    private static final int MAX_PREFERRED_TAGS = 5;
    private static final int MAX_PREFERRED_TAGS_PER_TYPE = 5;
    private static final long HIGH_RATED_REVIEW_WEIGHT = 2L;

    private final ContentViewRepository contentViewRepository;
    private final ReviewRepository reviewRepository;
    private final ContentTagRepository contentTagRepository;

    public RecommendationPreference createPreference(UUID userId) {
        List<ContentView> recentViews = contentViewRepository.findRecentByUserId(
                userId,
                PageRequest.of(0, RECENT_VIEW_LIMIT)
        );

        List<Review> highRatedReviews = reviewRepository.findHighRatedByUserId(
                userId,
                HIGH_RATING_THRESHOLD,
                PageRequest.of(0, HIGH_RATED_REVIEW_LIMIT)
        );

        // 시청 이력과 고평점 리뷰가 모두 없으면 AI 추천을 수행하지 않는 Cold Start
        if (recentViews.isEmpty() && highRatedReviews.isEmpty()) {
            return RecommendationPreference.forColdStart();
        }

        Set<UUID> interactedContentIds = collectInteractedContentIds(
                recentViews,
                highRatedReviews
        );

        Map<UUID, Long> contentPreferenceScores = calculateContentPreferenceScores(
                recentViews,
                highRatedReviews
        );

        List<ContentTag> contentTags = interactedContentIds.isEmpty()
                ? List.of()
                : contentTagRepository.findAllByContentIds(interactedContentIds);

        List<RecommendationPreferredTag> preferredTags = findPreferredTags(
                contentTags,
                contentPreferenceScores
        );

        Map<ContentType, List<RecommendationPreferredTag>> preferredTagsByType =
                findPreferredTagsByType(
                        contentTags,
                        contentPreferenceScores
                );

        String preferenceText = buildPreferenceText(
                recentViews,
                highRatedReviews,
                preferredTags
        );

        return RecommendationPreference.personalized(
                preferenceText,
                interactedContentIds,
                preferredTagsByType
        );
    }

    private Map<UUID, Long> calculateContentPreferenceScores(
            List<ContentView> recentViews,
            List<Review> highRatedReviews
    ) {
        Map<UUID, Long> scores = new HashMap<>();

        for (ContentView contentView : recentViews) {
            UUID contentId = contentView.getContent().getId();

            long viewScore = Math.max(
                    contentView.getViewCount(),
                    1L
            );

            scores.merge(
                    contentId,
                    viewScore,
                    Long::sum
            );
        }

        for (Review review : highRatedReviews) {
            UUID contentId = review.getContent().getId();

            scores.merge(
                    contentId,
                    HIGH_RATED_REVIEW_WEIGHT,
                    Long::sum
            );
        }

        return scores;
    }

    private List<RecommendationPreferredTag> findPreferredTags(
            List<ContentTag> contentTags,
            Map<UUID, Long> contentPreferenceScores
    ) {
        if (contentTags.isEmpty()) {
            return List.of();
        }

        Map<TagKey, Long> tagScores = new HashMap<>();

        for (ContentTag contentTag : contentTags) {
            UUID contentId = contentTag.getContent().getId();

            long contentScore = contentPreferenceScores.getOrDefault(
                    contentId,
                    0L
            );

            if (contentScore <= 0) {
                continue;
            }

            TagKey tagKey = new TagKey(
                    contentTag.getTag(),
                    contentTag.getValue()
            );

            tagScores.merge(
                    tagKey,
                    contentScore,
                    Long::sum
            );
        }

        return toPreferredTags(
                tagScores,
                MAX_PREFERRED_TAGS
        );
    }

    // 콘텐츠 타입별로 태그 점수를 독립적으로 계산
    private Map<ContentType, List<RecommendationPreferredTag>> findPreferredTagsByType(
            List<ContentTag> contentTags,
            Map<UUID, Long> contentPreferenceScores
    ) {
        if (contentTags.isEmpty()) {
            return Map.of();
        }

        Map<ContentType, Map<TagKey, Long>> tagScoresByType = new EnumMap<>(ContentType.class);

        for (ContentTag contentTag : contentTags) {
            UUID contentId = contentTag.getContent().getId();

            long contentScore = contentPreferenceScores.getOrDefault(
                    contentId,
                    0L
            );

            if (contentScore <= 0) {
                continue;
            }

            ContentType contentType = contentTag.getContent().getType();

            TagKey tagKey = new TagKey(
                    contentTag.getTag(),
                    contentTag.getValue()
            );

            tagScoresByType.computeIfAbsent(
                    contentType,
                    ignored -> new HashMap<>()
            )
                    .merge(tagKey, contentScore, Long::sum);
        }

        Map<ContentType, List<RecommendationPreferredTag>> preferredTagsByType =
                new EnumMap<>(ContentType.class);

        for (Map.Entry<ContentType, Map<TagKey, Long>> entry : tagScoresByType.entrySet()) {
            List<RecommendationPreferredTag> preferredTags = toPreferredTags(
                    entry.getValue(),
                    MAX_PREFERRED_TAGS_PER_TYPE
            );

            if (preferredTags.isEmpty()) {
                continue;
            }

            preferredTagsByType.put(entry.getKey(), preferredTags);
        }
        return preferredTagsByType;
    }

    private List<RecommendationPreferredTag> toPreferredTags(
            Map<TagKey, Long> tagScores,
            int limit
    ) {
        return tagScores.entrySet()
                .stream()
                .sorted(Map.Entry
                        .<TagKey, Long>comparingByValue()
                        .reversed()
                        .thenComparing(entry -> entry.getKey().tag())
                        .thenComparing(entry -> entry.getKey().value())
                )
                .limit(limit)
                .map(entry -> new RecommendationPreferredTag(
                        entry.getKey().tag(),
                        entry.getKey().value(),
                        entry.getValue()
                ))
                .toList();
    }

    private Set<UUID> collectInteractedContentIds(
            List<ContentView> recentViews,
            List<Review> highRatedReviews
    ) {
        Set<UUID> contentIds = new LinkedHashSet<>();

        recentViews.stream()
                .map(ContentView::getContent)
                .map(content -> content.getId())
                .forEach(contentIds::add);

        highRatedReviews.stream()
                .map(Review::getContent)
                .map(content -> content.getId())
                .forEach(contentIds::add);

        return contentIds;
    }

    private String buildPreferenceText(
            List<ContentView> recentViews,
            List<Review> highRatedReviews,
            List<RecommendationPreferredTag> preferredTags
    ) {
        StringBuilder builder = new StringBuilder();

        findPreferredContentType(recentViews, highRatedReviews).ifPresent(contentType ->
                builder.append("선호 콘텐츠 유형: ")
                        .append(contentType.name())
                        .append('\n'));

        if (!preferredTags.isEmpty()) {
            builder.append("선호 태그:\n");

            preferredTags.forEach(preferredTag -> builder.append("- ")
                    .append(preferredTag.tag())
                    .append(':')
                    .append(preferredTag.value())
                    .append('\n'));
        }

        List<String> highRatedTitles = highRatedReviews.stream()
                .limit(MAX_HIGH_RATED_TITLES)
                .map(review -> review.getContent().getTitle())
                .toList();

        if (!highRatedTitles.isEmpty()) {
            builder.append("높은 평점을 준 콘텐츠:\n");

            highRatedTitles.forEach(title ->
                    builder.append("- ")
                            .append(title)
                            .append('\n'));
        }

        List<String> frequentlyViewedTitles =
                recentViews.stream()
                        .sorted(
                                Comparator.comparing(
                                                ContentView::getViewCount,
                                                Comparator.reverseOrder()
                                        )
                                        .thenComparing(
                                                ContentView::getLastViewedAt,
                                                Comparator.reverseOrder()
                                        )
                        )
                        .limit(MAX_FREQUENT_VIEW_TITLES)
                        .map(contentView ->
                                contentView.getContent().getTitle())
                        .toList();

        if (!frequentlyViewedTitles.isEmpty()) {
            builder.append("자주 시청한 콘텐츠:\n");

            frequentlyViewedTitles.forEach(title ->
                    builder.append("- ")
                            .append(title)
                            .append('\n'));
        }

        return builder.toString().trim();
    }

    private Optional<ContentType> findPreferredContentType(
            List<ContentView> recentViews,
            List<Review> highRatedReviews
    ) {
        Map<ContentType, Long> scores = new EnumMap<>(ContentType.class);

        for (ContentView contentView : recentViews) {
            ContentType type = contentView.getContent().getType();

            long viewScore = Math.max(contentView.getViewCount(), 1L);

            scores.merge(
                    type,
                    viewScore,
                    Long::sum
            );
        }

        for (Review review : highRatedReviews) {
            ContentType type = review.getContent().getType();

            scores.merge(
                    type,
                    HIGH_RATED_REVIEW_WEIGHT,
                    Long::sum
            );
        }

        return scores.entrySet().stream()
                .max(Map.Entry
                        .<ContentType, Long>comparingByValue()
                        .thenComparing(entry -> entry.getKey().name()))
                .map(Map.Entry::getKey);
    }

    private record TagKey(String tag, String value) {}
}
