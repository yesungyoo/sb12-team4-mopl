package com.mopl.batch.external.sportsdb.dto;

import java.util.List;

public record SportsDbEventsResponse(
        List<SportsDbEvent> events
) {
}
