package com.mopl.content.dto;

import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ContentResponse(
        UUID id,
        ContentType type,
        String title,
        String description,
        String thumbnailUrl,
        ExternalSource externalSource,
        String externalId,
        LocalDate releaseDate,
        BigDecimal externalPopularity,
        BigDecimal externalRating,
        Long externalVoteCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ContentResponse from(Content content) {
        return new ContentResponse(
                content.getId(),
                content.getType(),
                content.getTitle(),
                content.getDescription(),
                content.getThumbnailUrl(),
                content.getExternalSource(),
                content.getExternalId(),
                content.getReleaseDate(),
                content.getExternalPopularity(),
                content.getExternalRating(),
                content.getExternalVoteCount(),
                content.getCreatedAt(),
                content.getUpdatedAt()
        );
    }
}
