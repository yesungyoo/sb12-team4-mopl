package com.mopl.batch.external.common.dto;

import java.time.LocalDate;
import java.util.List;

public record ExternalContentDto(

        // ERD 확정 시 ContentType type
        String type,

        String title,

        String description,

        String thumbnailUrl,

        // ERD 확정 시 ExternalSource externalSource
        String externalSource,

        String externalId,

        LocalDate releaseDate,

        Double externalPopularity,

        Double externalRating,

        Long externalVoteCount,

        List<Integer> externalGenreIds
) {
}
