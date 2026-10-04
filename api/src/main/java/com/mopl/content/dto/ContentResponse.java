package com.mopl.content.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ContentResponse(
        UUID id,
        ContentType type,
        String title,
        String description,
        String thumbnailUrl,
        List<String> tags,
        double averageRating,
        long reviewCount,
        long watcherCount,
        ExternalSource externalSource,
        String externalId,
        LocalDate releaseDate,
        BigDecimal externalPopularity,
        BigDecimal externalRating,
        Long externalVoteCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public ContentResponse(
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
        this(
                id,
                type,
                title,
                description,
                thumbnailUrl,
                List.of(),
                0.0,
                0L,
                0L,
                externalSource,
                externalId,
                releaseDate,
                externalPopularity,
                externalRating,
                externalVoteCount,
                createdAt,
                updatedAt
        );
    }

    @Override
    @JsonSerialize(using = ContentTypeApiSerializer.class)
    @Schema(
            description = "콘텐츠 타입",
            allowableValues = {"movie", "tvSeries", "sport"}
    )
    public ContentType type() {
        return type;
    }

    public static ContentResponse from(Content content) {
        return from(
                content,
                List.of(),
                null,
                0L,
                0L
        );
    }

    public static ContentResponse from(
            Content content,
            List<String> tags,
            Double averageRating,
            long reviewCount,
            long watcherCount
    ) {
        return new ContentResponse(
                content.getId(),
                content.getType(),
                content.getTitle(),
                content.getDescription(),
                content.getThumbnailUrl(),
                tags == null ? List.of() : List.copyOf(tags),
                averageRating == null ? 0.0 : averageRating,
                reviewCount,
                watcherCount,
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
