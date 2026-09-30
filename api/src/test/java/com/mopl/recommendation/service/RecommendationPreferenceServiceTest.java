package com.mopl.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.repository.ContentViewRepository;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.core.domain.content.entity.ContentView;
import com.mopl.core.domain.review.entity.Review;
import com.mopl.recommendation.dto.RecommendationPreference;
import com.mopl.review.repository.ReviewRepository;

@ExtendWith(MockitoExtension.class)
class RecommendationPreferenceServiceTest {

    @Mock
    private ContentViewRepository contentViewRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ContentTagRepository contentTagRepository;

    private RecommendationPreferenceService recommendationPreferenceService;

    @BeforeEach
    void setUp() {
        recommendationPreferenceService =
                new RecommendationPreferenceService(
                        contentViewRepository,
                        reviewRepository,
                        contentTagRepository
                );
    }

    @Test
    void returnsColdStartWhenUserHasNoHistory() {
        UUID userId =
                UUID.randomUUID();

        when(contentViewRepository.findRecentByUserId(
                eq(userId),
                any(Pageable.class)
        )).thenReturn(
                List.of()
        );

        when(reviewRepository.findHighRatedByUserId(
                eq(userId),
                eq(new BigDecimal("4.0")),
                any(Pageable.class)
        )).thenReturn(
                List.of()
        );

        RecommendationPreference preference =
                recommendationPreferenceService.createPreference(
                        userId
                );

        assertThat(preference.coldStart())
                .isTrue();

        assertThat(preference.preferenceText())
                .isEmpty();

        assertThat(preference.interactedContentIds())
                .isEmpty();

        verify(
                contentTagRepository,
                never()
        ).findAllByContentIds(
                any()
        );
    }

    @Test
    void createsPreferenceFromViewsReviewsAndTags() {
        UUID userId =
                UUID.randomUUID();

        Content movie =
                mockContent(
                        UUID.randomUUID(),
                        ContentType.MOVIE,
                        "Interstellar"
                );

        Content tvSeries =
                mockContent(
                        UUID.randomUUID(),
                        ContentType.TV_SERIES,
                        "Dark"
                );

        ContentView movieView =
                mockContentView(
                        movie,
                        5L
                );

        ContentView tvView =
                mockContentView(
                        tvSeries,
                        1L
                );

        Review movieReview =
                mockReview(
                        movie
                );

        ContentTag movieSfTag =
                mockContentTag(
                        movie,
                        "GENRE",
                        "SF"
                );

        ContentTag movieDramaTag =
                mockContentTag(
                        movie,
                        "GENRE",
                        "DRAMA"
                );

        ContentTag tvDramaTag =
                mockContentTag(
                        tvSeries,
                        "GENRE",
                        "DRAMA"
                );

        when(contentViewRepository.findRecentByUserId(
                eq(userId),
                any(Pageable.class)
        )).thenReturn(
                List.of(
                        movieView,
                        tvView
                )
        );

        when(reviewRepository.findHighRatedByUserId(
                eq(userId),
                eq(new BigDecimal("4.0")),
                any(Pageable.class)
        )).thenReturn(
                List.of(
                        movieReview
                )
        );

        when(contentTagRepository.findAllByContentIds(any()))
                .thenReturn(
                        List.of(
                                movieSfTag,
                                movieDramaTag,
                                tvDramaTag
                        )
                );

        RecommendationPreference preference =
                recommendationPreferenceService.createPreference(
                        userId
                );

        assertThat(preference.coldStart())
                .isFalse();

        assertThat(preference.interactedContentIds())
                .containsExactlyInAnyOrder(
                        movie.getId(),
                        tvSeries.getId()
                );

        assertThat(preference.preferenceText())
                .contains(
                        "선호 콘텐츠 유형: MOVIE",
                        "선호 태그:",
                        "- GENRE:DRAMA",
                        "- GENRE:SF",
                        "높은 평점을 준 콘텐츠:",
                        "- Interstellar",
                        "자주 시청한 콘텐츠:",
                        "- Interstellar",
                        "- Dark"
                );

        verify(contentViewRepository)
                .findRecentByUserId(
                        eq(userId),
                        any(Pageable.class)
                );

        verify(reviewRepository)
                .findHighRatedByUserId(
                        eq(userId),
                        eq(new BigDecimal("4.0")),
                        any(Pageable.class)
                );

        verify(contentTagRepository)
                .findAllByContentIds(
                        any()
                );
    }

    private Content mockContent(
            UUID contentId,
            ContentType contentType,
            String title
    ) {
        Content content =
                mock(Content.class);

        when(content.getId())
                .thenReturn(contentId);

        when(content.getType())
                .thenReturn(contentType);

        when(content.getTitle())
                .thenReturn(title);

        return content;
    }

    private ContentView mockContentView(
            Content content,
            long viewCount
    ) {
        ContentView contentView =
                mock(ContentView.class);

        when(contentView.getContent())
                .thenReturn(content);

        when(contentView.getViewCount())
                .thenReturn(viewCount);

        return contentView;
    }

    private Review mockReview(
            Content content
    ) {
        Review review =
                mock(Review.class);

        when(review.getContent())
                .thenReturn(content);

        return review;
    }

    private ContentTag mockContentTag(
            Content content,
            String tag,
            String value
    ) {
        ContentTag contentTag =
                mock(ContentTag.class);

        when(contentTag.getContent())
                .thenReturn(content);

        when(contentTag.getTag())
                .thenReturn(tag);

        when(contentTag.getValue())
                .thenReturn(value);

        return contentTag;
    }
}