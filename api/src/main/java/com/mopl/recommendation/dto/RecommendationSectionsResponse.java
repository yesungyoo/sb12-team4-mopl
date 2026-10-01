package com.mopl.recommendation.dto;

import java.util.List;

public record RecommendationSectionsResponse(
        List<RecommendationSection> sections
) {

    public RecommendationSectionsResponse {
        sections = sections == null
                ? List.of()
                : List.copyOf(sections);
    }

    public static RecommendationSectionsResponse of(
            List<RecommendationSection> sections
    ) {
        return new RecommendationSectionsResponse(sections);
    }

    public static RecommendationSectionsResponse empty() {
        return new RecommendationSectionsResponse(List.of());
    }
}
