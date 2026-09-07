package com.mopl.batch.external.sportsdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

public record SportsDbEvent(

        @JsonProperty("idEvent")
        String id,

        @JsonProperty("strEvent")
        String eventName,

        @JsonProperty("strSport")
        String sport,

        @JsonProperty("strLeague")
        String leagueName,

        @JsonProperty("strHomeTeam")
        String homeTeamName,

        @JsonProperty("strAwayTeam")
        String awayTeamName,

        @JsonProperty("dateEvent")
        LocalDate eventDate,

        @JsonProperty("strTimestamp")
        String timestamp,

        @JsonProperty("strStatus")
        String status,

        @JsonProperty("strThumb")
        String thumbnailUrl
) {
}
