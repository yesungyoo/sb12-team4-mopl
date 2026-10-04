package com.mopl.batch.external.sportsdb.client;

import com.mopl.batch.external.sportsdb.dto.SportsDbEventLookupResponse;
import com.mopl.batch.external.sportsdb.dto.SportsDbEventsResponse;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SportsDbClient {

    private final RestClient restClient;

    public SportsDbClient(@Qualifier("sportsDbRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public SportsDbEventsResponse getEventsByDate(LocalDate date) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/eventsday.php")
                        .queryParam("d", date)
                        .build())
                .retrieve()
                .body(SportsDbEventsResponse.class);
    }

    public SportsDbEventsResponse getEventsByDate(LocalDate date, String sport) {
        if (sport == null || sport.isBlank()) {
            return getEventsByDate(date);
        }

        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/eventsday.php")
                        .queryParam("d", date)
                        .queryParam("s", sport)
                        .build())
                .retrieve()
                .body(SportsDbEventsResponse.class);
    }

    public SportsDbEventLookupResponse getEventById(String externalId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/lookupevent.php")
                        .queryParam("id", externalId)
                        .build())
                .retrieve()
                .body(SportsDbEventLookupResponse.class);
    }
}
