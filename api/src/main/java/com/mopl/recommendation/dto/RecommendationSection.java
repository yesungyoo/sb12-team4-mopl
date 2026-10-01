package com.mopl.recommendation.dto;

import com.mopl.core.common.enums.ContentType;

import java.util.List;

public record RecommendationSection(
        String key,
        String title,
        String description,
        ContentType contentType,
        String tag,
        List<RecommendationSectionItem> items
) {

    public RecommendationSection {
        items = items == null
                ? List.of()
                : List.copyOf(items);
    }
}
