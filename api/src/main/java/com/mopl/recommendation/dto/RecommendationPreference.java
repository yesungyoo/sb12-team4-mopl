package com.mopl.recommendation.dto;

import java.util.Set;
import java.util.UUID;

public record RecommendationPreference(
        boolean coldStart,
        String preferenceText,
        Set<UUID> interactedContentIds
) {

    public static RecommendationPreference forColdStart() {
        return new RecommendationPreference(
                true,
                "",
                Set.of()
        );
    }

    public static RecommendationPreference personalized(
            String preferenceText,
            Set<UUID> interactedContentIds
    ) {
        return new RecommendationPreference(
                false,
                preferenceText,
                Set.copyOf(interactedContentIds)
        );
    }
}
