package com.mopl.recommendation.dto;

public record RecommendationPreferredTag(
        String tag,
        String value,
        long score
) {
}
