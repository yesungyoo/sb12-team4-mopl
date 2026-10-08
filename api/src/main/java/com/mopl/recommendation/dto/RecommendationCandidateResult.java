package com.mopl.recommendation.dto;

import com.mopl.content.search.dto.ContentCandidate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record RecommendationCandidateResult(
        boolean coldStart,
        String preferenceText,
        List<ContentCandidate> candidates,
        Set<UUID> interactedContentIds,
        RecommendationFallbackReason fallbackReason,
        boolean cacheable
) {

    public static RecommendationCandidateResult forColdStart() {
        return new RecommendationCandidateResult(
                true,
                "",
                List.of(),
                Set.of(),
                null,
                true
        );
    }

    public static RecommendationCandidateResult forPopularFallback(
            Set<UUID> interactedContentIds,
            RecommendationFallbackReason fallbackReason,
            boolean cacheable
    ) {
        return new RecommendationCandidateResult(
                true,
                "",
                List.of(),
                Set.copyOf(interactedContentIds),
                fallbackReason,
                cacheable
        );
    }

    public static RecommendationCandidateResult personalized(
            String preferenceText,
            List<ContentCandidate> candidates
    ) {
        return personalized(
                preferenceText,
                candidates,
                Set.of()
        );
    }

    public static RecommendationCandidateResult personalized(
            String preferenceText,
            List<ContentCandidate> candidates,
            Set<UUID> interactedContentIds
    ) {
        return new RecommendationCandidateResult(
                false,
                preferenceText,
                List.copyOf(candidates),
                Set.copyOf(interactedContentIds),
                null,
                true
        );
    }
}
