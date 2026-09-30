package com.mopl.recommendation.dto;

import com.mopl.content.search.dto.ContentCandidate;

import java.util.List;

public record RecommendationCandidateResult(
        boolean coldStart,
        String preferenceText,
        List<ContentCandidate> candidates
) {

    public static RecommendationCandidateResult forColdStart() {
        return new RecommendationCandidateResult(
                true,
                "",
                List.of()
        );
    }

    public static RecommendationCandidateResult personalized(
            String preferenceText,
            List<ContentCandidate> candidates
    ) {
        return new RecommendationCandidateResult(
                false,
                preferenceText,
                List.copyOf(candidates)
        );
    }
}
