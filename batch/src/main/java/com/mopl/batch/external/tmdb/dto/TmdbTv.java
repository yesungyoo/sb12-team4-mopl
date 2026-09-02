package com.mopl.batch.external.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

public record TmdbTv(

        Long id,
        String name,
        String overview,

        @JsonProperty("first_air_date")
        LocalDate firstAirDate,

        @JsonProperty("poster_path")
        String posterPath,

        @JsonProperty("backdrop_path")
        String backdropPath,

        @JsonProperty("genre_ids")
        List<Integer> genreIds,

        Double popularity,

        @JsonProperty("vote_average")
        Double voteAverage,

        @JsonProperty("vote_count")
        Long voteCount
) {
}
