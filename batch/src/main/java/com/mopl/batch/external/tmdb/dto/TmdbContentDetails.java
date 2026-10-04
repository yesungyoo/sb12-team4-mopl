package com.mopl.batch.external.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbContentDetails(
        List<Genre> genres
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Genre(
            Integer id
    ) {
    }
}
