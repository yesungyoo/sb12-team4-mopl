package com.mopl.recommendation.dto;

import java.util.List;
import java.util.Objects;

public record RecommendationResult(
        RecommendationResultType type,
        List<RecommendationItem> items
) {

    public RecommendationResult {
        type = Objects.requireNonNull(type);
        items = List.copyOf(Objects.requireNonNull(items));
    }

    public static RecommendationResult personalized(
            List<RecommendationItem> items
    ) {
        return new RecommendationResult(
                RecommendationResultType.PERSONALIZED,
                items
        );
    }

    public static RecommendationResult popular(
            List<RecommendationItem> items
    ) {
        return new RecommendationResult(
                RecommendationResultType.POPULAR,
                items
        );
    }
}
