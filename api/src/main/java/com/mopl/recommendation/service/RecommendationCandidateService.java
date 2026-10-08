package com.mopl.recommendation.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mopl.recommendation.config.RecommendationProperties;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.exception.AiClientException;
import org.springframework.stereotype.Service;

import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.content.search.service.SemanticCandidateSearchService;
import com.mopl.recommendation.dto.RecommendationCandidateResult;
import com.mopl.recommendation.dto.RecommendationFallbackReason;
import com.mopl.recommendation.dto.RecommendationPreference;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationCandidateService {

    private final RecommendationPreferenceService recommendationPreferenceService;
    private final SemanticCandidateSearchService semanticCandidateSearchService;
    private final RecommendationProperties recommendationProperties;
    private final AiAvailability aiAvailability;

    public RecommendationCandidateResult findCandidates(UUID userId) {
        RecommendationPreference preference =
                recommendationPreferenceService.createPreference(userId);

        if (preference.coldStart()) {
            return RecommendationCandidateResult.forColdStart();
        }

        if (!aiAvailability.isAvailable()) {
            AiAvailability.UnavailableReason unavailableReason =
                    aiAvailability.unavailableReason();

            RecommendationFallbackReason fallbackReason =
                    switch (unavailableReason) {
                        case AI_DISABLED ->
                                RecommendationFallbackReason.AI_DISABLED;
                        case API_KEY_MISSING ->
                                RecommendationFallbackReason.API_KEY_MISSING;
                    };

            log.debug(
                    "AI를 사용할 수 없어 인기 추천으로 fallback 합니다. userId={}, reason={}",
                    userId,
                    fallbackReason
            );

            return RecommendationCandidateResult.forPopularFallback(
                    preference.interactedContentIds(),
                    fallbackReason,
                    true
            );
        }

        int retrievalSize = recommendationProperties.candidateSize() + preference
                .interactedContentIds()
                .size();

        List<ContentCandidate> retrievedCandidates;

        try {
            retrievedCandidates = semanticCandidateSearchService.search(
                    preference.preferenceText(),
                    null,
                    retrievalSize
            );
        } catch (AiClientException exception) {
            log.warn(
                    "Embedding API 실패로 인기 추천으로 fallback 합니다. userId={}, reason={}",
                    userId,
                    RecommendationFallbackReason.EMBEDDING_API_FAILED,
                    exception
            );

            return RecommendationCandidateResult.forPopularFallback(
                    preference.interactedContentIds(),
                    RecommendationFallbackReason.EMBEDDING_API_FAILED,
                    false
            );
        }

        if (retrievedCandidates.isEmpty()) {
            log.debug(
                    "시맨틱 후보가 없어 인기 추천으로 fallback 합니다. userId={}, reason={}",
                    userId,
                    RecommendationFallbackReason.NO_SEMANTIC_CANDIDATE
            );

            return RecommendationCandidateResult.forPopularFallback(
                    preference.interactedContentIds(),
                    RecommendationFallbackReason.NO_SEMANTIC_CANDIDATE,
                    true
            );
        }

        List<ContentCandidate> candidates = excludeInteractedContents(
                retrievedCandidates,
                preference.interactedContentIds()
        );

        if (candidates.isEmpty()) {
            log.debug(
                    "상호작용 콘텐츠 제외 후 후보가 없어 인기 추천으로 fallback 합니다. userId={}, reason={}",
                    userId,
                    RecommendationFallbackReason.ALL_CANDIDATES_EXCLUDED
            );

            return RecommendationCandidateResult.forPopularFallback(
                    preference.interactedContentIds(),
                    RecommendationFallbackReason.ALL_CANDIDATES_EXCLUDED,
                    true
            );
        }

        return RecommendationCandidateResult.personalized(
                preference.preferenceText(),
                candidates,
                preference.interactedContentIds()
        );
    }

    private List<ContentCandidate> excludeInteractedContents(
            List<ContentCandidate> candidates,
            Set<UUID> interactedContentIds
    ) {
        return candidates.stream()
                .filter(candidate -> !interactedContentIds.contains(
                        candidate.contentId()
                ))
                .limit(recommendationProperties.candidateSize())
                .toList();
    }
}
