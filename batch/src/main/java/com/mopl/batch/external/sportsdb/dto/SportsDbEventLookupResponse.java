package com.mopl.batch.external.sportsdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SportsDbEventLookupResponse(
        List<SportsDbEvent> events
) {
}
