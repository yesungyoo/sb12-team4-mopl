package com.mopl.batch.external.sportsdb.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.common.dto.ExternalContentTagDto;
import com.mopl.batch.external.sportsdb.dto.SportsDbEvent;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SportsDbContentMapperTest {

    private final SportsDbContentMapper mapper =
            new SportsDbContentMapper();

    @Test
    void fromEvent() {
        SportsDbEvent event = new SportsDbEvent(
                "2432585",
                "Saskatchewan Roughriders vs Winnipeg Blue Bombers",
                "American Football",
                "CFL",
                "Saskatchewan Roughriders",
                "Winnipeg Blue Bombers",
                LocalDate.of(2026, 9, 6),
                "2026-09-06T23:00:00",
                "NS",
                "https://r2.thesportsdb.com/images/media/event/thumb/test.jpg"
        );

        ExternalContentDto result =
                mapper.fromEvent(event);

        assertThat(result.type()).isEqualTo("SPORT");
        assertThat(result.title())
                .isEqualTo(
                        "Saskatchewan Roughriders vs Winnipeg Blue Bombers"
                );
        assertThat(result.thumbnailUrl())
                .isEqualTo(
                        "https://r2.thesportsdb.com/images/media/event/thumb/test.jpg"
                );
        assertThat(result.externalSource())
                .isEqualTo("THESPORTSDB");
        assertThat(result.externalId())
                .isEqualTo("2432585");
        assertThat(result.releaseDate())
                .isEqualTo(
                        LocalDate.of(2026, 9, 6)
                );
        assertThat(result.externalPopularity()).isNull();
        assertThat(result.externalRating()).isNull();
        assertThat(result.externalVoteCount()).isNull();

        assertThat(result.tags())
                .containsExactly(
                        new ExternalContentTagDto(
                                "SPORT",
                                "American Football"
                        ),
                        new ExternalContentTagDto(
                                "LEAGUE",
                                "CFL"
                        )
                );
    }

    @Test
    void fromEventWithBlankThumbnail() {
        SportsDbEvent event = new SportsDbEvent(
                "2498651",
                "Maryland vs Hampton",
                "American Football",
                "NCAA Division 1 Football",
                "Maryland",
                "Hampton",
                LocalDate.of(2026, 9, 6),
                "2026-09-06T00:00:00",
                "FT",
                ""
        );

        ExternalContentDto result =
                mapper.fromEvent(event);

        assertThat(result.thumbnailUrl()).isNull();

        assertThat(result.tags())
                .containsExactly(
                        new ExternalContentTagDto(
                                "SPORT",
                                "American Football"
                        ),
                        new ExternalContentTagDto(
                                "LEAGUE",
                                "NCAA Division 1 Football"
                        )
                );
    }
}
