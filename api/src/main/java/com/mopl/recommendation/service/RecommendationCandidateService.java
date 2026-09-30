package com.mopl.recommendation.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mopl.recommendation.config.RecommendationProperties;
import org.springframework.stereotype.Service;

import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.content.search.service.SemanticCandidateSearchService;
import com.mopl.recommendation.dto.RecommendationCandidateResult;
import com.mopl.recommendation.dto.RecommendationPreference;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecommendationCandidateService {

    private final RecommendationPreferenceService recommendationPreferenceService;
    private final SemanticCandidateSearchService semanticCandidateSearchService;
    private final RecommendationProperties recommendationProperties;

    public RecommendationCandidateResult findCandidates(UUID userId) {
        RecommendationPreference preference =
                recommendationPreferenceService.createPreference(userId);

        if (preference.coldStart()) {
            return RecommendationCandidateResult.forColdStart();
        }

        int retrievalSize = recommendationProperties.candidateSize() + preference
                .interactedContentIds()
                .size();

        List<ContentCandidate> retrievedCandidates =
                semanticCandidateSearchService.search(
                        preference.preferenceText(),
                        null,
                        retrievalSize
                );

        List<ContentCandidate> candidates = excludeInteractedContents(
                retrievedCandidates,
                preference.interactedContentIds()
        );

        return RecommendationCandidateResult.personalized(
                preference.preferenceText(),
                candidates
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
