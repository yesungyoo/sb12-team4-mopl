package com.mopl.batch.external.common.dto;

import java.time.LocalDate;
import java.util.List;

public record ExternalContentDto(
        String type,
        String title,
        String description,
        String thumbnailUrl,
        String externalSource,
        String externalId,
        LocalDate releaseDate,
        Double externalPopularity,
        Double externalRating,
        Long externalVoteCount,
        List<ExternalContentTagDto> tags
) {

    public ExternalContentDto {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
