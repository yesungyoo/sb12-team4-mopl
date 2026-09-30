package com.mopl.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.core.common.enums.ContentType;
import com.mopl.recommendation.dto.RecommendationCandidateResult;
import com.mopl.recommendation.dto.RecommendationItem;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private RecommendationCacheService recommendationCacheService;

    @Mock
    private RecommendationCandidateService recommendationCandidateService;

    @Mock
    private RecommendationRerankService recommendationRerankService;

    @Mock
    private ColdStartRecommendationService coldStartRecommendationService;

    private RecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        recommendationService =
                new RecommendationService(
                        recommendationCacheService,
                        recommendationCandidateService,
                        recommendationRerankService,
                        coldStartRecommendationService
                );
    }

    @Test
    void returnsCachedRecommendationsWithoutCreatingNewRecommendation() {
        UUID userId =
                UUID.randomUUID();

        RecommendationItem cachedRecommendation =
                createRecommendationItem(
                        UUID.randomUUID(),
                        "Cached Content"
                );

        when(recommendationCacheService.get(userId))
                .thenReturn(
                        Optional.of(
                                List.of(cachedRecommendation)
                        )
                );

        List<RecommendationItem> result =
                recommendationService.getRecommendations(
                        userId
                );

        assertThat(result)
                .containsExactly(
                        cachedRecommendation
                );

        verify(
                recommendationCandidateService,
                never()
        ).findCandidates(
                userId
        );

        verify(
                recommendationRerankService,
                never()
        ).rerank(
                anyString(),
                anyList()
        );

        verify(
                coldStartRecommendationService,
                never()
        ).recommend();

        verify(
                recommendationCacheService,
                never()
        ).save(
                any(),
                anyList()
        );
    }

    @Test
    void createsPersonalizedRecommendationOnCacheMiss() {
        UUID userId =
                UUID.randomUUID();

        String preferenceText =
                """
                선호 콘텐츠 유형: MOVIE
                선호 태그:
                - GENRE:SF
                """.trim();

        ContentCandidate candidate =
                createCandidate(
                        UUID.randomUUID(),
                        "Interstellar"
                );

        RecommendationCandidateResult candidateResult =
                RecommendationCandidateResult.personalized(
                        preferenceText,
                        List.of(candidate)
                );

        RecommendationItem recommendation =
                createRecommendationItem(
                        candidate.contentId(),
                        candidate.title()
                );

        when(recommendationCacheService.get(userId))
                .thenReturn(
                        Optional.empty()
                );

        when(recommendationCandidateService.findCandidates(userId))
                .thenReturn(
                        candidateResult
                );

        when(recommendationRerankService.rerank(
                preferenceText,
                List.of(candidate)
        )).thenReturn(
                List.of(recommendation)
        );

        List<RecommendationItem> result =
                recommendationService.getRecommendations(
                        userId
                );

        assertThat(result)
                .containsExactly(
                        recommendation
                );

        verify(recommendationRerankService)
                .rerank(
                        preferenceText,
                        List.of(candidate)
                );

        verify(
                coldStartRecommendationService,
                never()
        ).recommend();

        verify(recommendationCacheService)
                .save(
                        userId,
                        List.of(recommendation)
                );
    }

    @Test
    void createsColdStartRecommendationWithoutLlm() {
        UUID userId =
                UUID.randomUUID();

        RecommendationItem coldStartRecommendation =
                createRecommendationItem(
                        UUID.randomUUID(),
                        "Popular Content"
                );

        when(recommendationCacheService.get(userId))
                .thenReturn(
                        Optional.empty()
                );

        when(recommendationCandidateService.findCandidates(userId))
                .thenReturn(
                        RecommendationCandidateResult.forColdStart()
                );

        when(coldStartRecommendationService.recommend())
                .thenReturn(
                        List.of(coldStartRecommendation)
                );

        List<RecommendationItem> result =
                recommendationService.getRecommendations(
                        userId
                );

        assertThat(result)
                .containsExactly(
                        coldStartRecommendation
                );

        verify(coldStartRecommendationService)
                .recommend();

        verify(
                recommendationRerankService,
                never()
        ).rerank(
                anyString(),
                anyList()
        );

        verify(recommendationCacheService)
                .save(
                        userId,
                        List.of(coldStartRecommendation)
                );
    }

    private ContentCandidate createCandidate(
            UUID contentId,
            String title
    ) {
        return new ContentCandidate(
                contentId,
                title,
                ContentType.MOVIE,
                List.of(),
                0.95,
                8.5,
                100.0,
                1000L
        );
    }

    private RecommendationItem createRecommendationItem(
            UUID contentId,
            String title
    ) {
        return new RecommendationItem(
                contentId,
                title,
                ContentType.MOVIE,
                List.of(),
                0.95,
                8.5,
                100.0,
                1000L,
                "추천 이유"
        );
    }
}