package com.mopl.content.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.document.ContentTagSearchDocument;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record ContentListItemResponse(
        UUID id,
        ContentType type,
        String title,
        String description,
        String thumbnailUrl,
        List<String> tags,
        double averageRating,
        long reviewCount,
        long watcherCount
) {

    @Override
    @JsonSerialize(using = ContentTypeApiSerializer.class)
    @Schema(
            description = "콘텐츠 타입",
            allowableValues = {"movie", "tvSeries", "sport"}
    )
    public ContentType type() {
        return type;
    }

    public static ContentListItemResponse from(
            Content content,
            ContentSearchDocument document
    ) {
        List<String> tags = document.getTags() == null
                ? List.of()
                : document.getTags()
                .stream()
                .map(ContentTagSearchDocument::getValue)
                .distinct()
                .toList();

        return new ContentListItemResponse(
                content.getId(),
                content.getType(),
                content.getTitle(),
                content.getDescription(),
                content.getThumbnailUrl(),
                tags,
                document.getAverageRating() == null
                        ? 0.0
                        : document.getAverageRating(),
                document.getReviewCount() == null
                        ? 0L
                        : document.getReviewCount(),
                document.getWatcherCount() == null
                        ? 0L
                        : document.getWatcherCount()
        );
    }
}
