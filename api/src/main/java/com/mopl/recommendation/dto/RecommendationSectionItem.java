package com.mopl.recommendation.dto;

import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.dto.ContentTagDto;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.Content;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record RecommendationSectionItem(
        UUID contentId,
        String title,
        String thumbnailUrl,
        ContentType type,
        List<ContentTagDto> tags,
        Double averageRating,
        long reviewCount,
        long watcherCount,
        LocalDateTime createdAt
) {

    public static RecommendationSectionItem from(
            Content content,
            ContentSearchDocument searchDocument
    ) {
        List<ContentTagDto> tags = searchDocument.getTags() == null
                ? List.of()
                : searchDocument.getTags().stream()
                        .map(ContentTagDto::from)
                        .toList();

        return new RecommendationSectionItem(
                content.getId(),
                content.getTitle(),
                content.getThumbnailUrl(),
                content.getType(),
                tags,
                searchDocument.getAverageRating(),
                defaultLong(searchDocument.getReviewCount()),
                defaultLong(searchDocument.getWatcherCount()),
                content.getCreatedAt()
        );
    }

    private static long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
