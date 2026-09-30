package com.mopl.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.content.search.service.SemanticCandidateSearchService;
import com.mopl.core.common.enums.ContentType;
import com.mopl.recommendation.config.RecommendationProperties;
import com.mopl.recommendation.dto.RecommendationCandidateResult;
import com.mopl.recommendation.dto.RecommendationPreference;

@ExtendWith(MockitoExtension.class)
class RecommendationCandidateServiceTest {

    @Mock
    private RecommendationPreferenceService recommendationPreferenceService;

    @Mock
    private SemanticCandidateSearchService semanticCandidateSearchService;

    private RecommendationProperties recommendationProperties;

    private RecommendationCandidateService recommendationCandidateService;

    @BeforeEach
    void setUp() {
        // [추가]
        recommendationProperties =
                new RecommendationProperties(
                        30,
                        10,
                        Duration.ofHours(6)
                );

        // [수정]
        recommendationCandidateService =
                new RecommendationCandidateService(
                        recommendationPreferenceService,
                        semanticCandidateSearchService,
                        recommendationProperties
                );
    }

    @Test
    void returnsColdStartWithoutSemanticSearchWhenPreferenceIsColdStart() {
        UUID userId =
                UUID.randomUUID();

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(
                        RecommendationPreference.forColdStart()
                );

        RecommendationCandidateResult result =
                recommendationCandidateService.findCandidates(
                        userId
                );

        assertThat(result.coldStart())
                .isTrue();

        assertThat(result.preferenceText())
                .isEmpty();

        assertThat(result.candidates())
                .isEmpty();

        verify(
                semanticCandidateSearchService,
                never()
        ).search(
                anyString(),
                any(),
                anyInt()
        );
    }

    @Test
    void excludesInteractedContentsAndPreservesSemanticOrder() {
        UUID userId =
                UUID.randomUUID();

        UUID interactedContentId =
                UUID.randomUUID();

        UUID firstCandidateId =
                UUID.randomUUID();

        UUID secondCandidateId =
                UUID.randomUUID();

        String preferenceText =
                """
                선호 콘텐츠 유형: MOVIE
                선호 태그:
                - GENRE:SF
                """.trim();

        RecommendationPreference preference =
                RecommendationPreference.personalized(
                        preferenceText,
                        Set.of(interactedContentId)
                );

        ContentCandidate interactedCandidate =
                createCandidate(
                        interactedContentId,
                        "Already Watched",
                        0.99
                );

        ContentCandidate firstCandidate =
                createCandidate(
                        firstCandidateId,
                        "First Candidate",
                        0.95
                );

        ContentCandidate secondCandidate =
                createCandidate(
                        secondCandidateId,
                        "Second Candidate",
                        0.90
                );

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);

        when(semanticCandidateSearchService.search(
                preferenceText,
                null,
                31
        )).thenReturn(
                List.of(
                        interactedCandidate,
                        firstCandidate,
                        secondCandidate
                )
        );

        RecommendationCandidateResult result =
                recommendationCandidateService.findCandidates(
                        userId
                );

        assertThat(result.coldStart())
                .isFalse();

        assertThat(result.preferenceText())
                .isEqualTo(preferenceText);

        assertThat(result.candidates())
                .extracting(ContentCandidate::contentId)
                .containsExactly(
                        firstCandidateId,
                        secondCandidateId
                );

        assertThat(result.candidates())
                .extracting(ContentCandidate::contentId)
                .doesNotContain(
                        interactedContentId
                );

        verify(semanticCandidateSearchService)
                .search(
                        preferenceText,
                        null,
                        31
                );
    }

    @Test
    void limitsCandidatesToThirtyAfterExcludingInteractedContents() {
        UUID userId =
                UUID.randomUUID();

        String preferenceText =
                "선호 콘텐츠 유형: MOVIE";

        List<ContentCandidate> retrievedCandidates =
                new ArrayList<>();

        Set<UUID> interactedContentIds =
                new HashSet<>();

        for (int index = 0; index < 5; index++) {
            UUID contentId =
                    UUID.randomUUID();

            interactedContentIds.add(
                    contentId
            );

            retrievedCandidates.add(
                    createCandidate(
                            contentId,
                            "Interacted " + index,
                            1.0 - (index * 0.01)
                    )
            );
        }

        for (int index = 0; index < 35; index++) {
            retrievedCandidates.add(
                    createCandidate(
                            UUID.randomUUID(),
                            "Candidate " + index,
                            0.9 - (index * 0.01)
                    )
            );
        }

        RecommendationPreference preference =
                RecommendationPreference.personalized(
                        preferenceText,
                        interactedContentIds
                );

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);

        when(semanticCandidateSearchService.search(
                preferenceText,
                null,
                35
        )).thenReturn(
                retrievedCandidates
        );

        RecommendationCandidateResult result =
                recommendationCandidateService.findCandidates(
                        userId
                );

        assertThat(result.candidates())
                .hasSize(30);

        assertThat(result.candidates())
                .extracting(ContentCandidate::contentId)
                .doesNotContainAnyElementsOf(
                        interactedContentIds
                );

        assertThat(result.candidates())
                .extracting(ContentCandidate::title)
                .containsExactly(
                        "Candidate 0",
                        "Candidate 1",
                        "Candidate 2",
                        "Candidate 3",
                        "Candidate 4",
                        "Candidate 5",
                        "Candidate 6",
                        "Candidate 7",
                        "Candidate 8",
                        "Candidate 9",
                        "Candidate 10",
                        "Candidate 11",
                        "Candidate 12",
                        "Candidate 13",
                        "Candidate 14",
                        "Candidate 15",
                        "Candidate 16",
                        "Candidate 17",
                        "Candidate 18",
                        "Candidate 19",
                        "Candidate 20",
                        "Candidate 21",
                        "Candidate 22",
                        "Candidate 23",
                        "Candidate 24",
                        "Candidate 25",
                        "Candidate 26",
                        "Candidate 27",
                        "Candidate 28",
                        "Candidate 29"
                );

        verify(semanticCandidateSearchService)
                .search(
                        preferenceText,
                        null,
                        35
                );
    }

    private ContentCandidate createCandidate(
            UUID contentId,
            String title,
            double semanticScore
    ) {
        return new ContentCandidate(
                contentId,
                title,
                ContentType.MOVIE,
                List.of(),
                semanticScore,
                null,
                null,
                null
        );
    }
}