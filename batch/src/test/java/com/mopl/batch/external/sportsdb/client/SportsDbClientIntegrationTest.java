package com.mopl.batch.external.sportsdb.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.batch.external.sportsdb.dto.SportsDbEvent;
import com.mopl.batch.external.sportsdb.dto.SportsDbEventsResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

class SportsDbClientIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2026, 9, 6);
    private static final String BASE_URL = "https://www.thesportsdb.com/api/v1/json/";

    @Test
    @EnabledIfEnvironmentVariable(
            named = "SPORTSDB_API_KEY",
            matches = ".+"
    )
    void getEventsByDate() {
        String apiKey = System.getenv("SPORTSDB_API_KEY");

        RestClient restClient = RestClient.builder()
                .baseUrl(BASE_URL + apiKey)
                .build();

        SportsDbClient client = new SportsDbClient(restClient);

        SportsDbEventsResponse response = client.getEventsByDate(TEST_DATE);

        assertThat(response).isNotNull();

        List<SportsDbEvent> events = response.events();

        assertThat(events)
                .isNotNull()
                .isNotEmpty();

        SportsDbEvent firstEvent = events.get(0);

        assertThat(firstEvent.id()).isNotBlank();
        assertThat(firstEvent.eventName()).isNotBlank();
        assertThat(firstEvent.sport()).isNotBlank();
        assertThat(firstEvent.eventDate()).isEqualTo(TEST_DATE);
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "SPORTSDB_API_KEY",
            matches = ".+"
    )
    void getEventsByDateAndSport() {
        String apiKey = System.getenv("SPORTSDB_API_KEY");

        RestClient restClient = RestClient.builder()
                .baseUrl(BASE_URL + apiKey)
                .build();

        SportsDbClient client = new SportsDbClient(restClient);

        SportsDbEventsResponse response = client.getEventsByDate(
                TEST_DATE,
                "American Football"
        );

        assertThat(response).isNotNull();
        assertThat(response.events())
                .isNotNull()
                .isNotEmpty()
                .allSatisfy(event ->
                        assertThat(event.sport()).isEqualTo("American Football")
                );
    }
}