package com.mopl.batch.external.sportsdb.mapper;

import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.common.dto.ExternalContentTagDto;
import com.mopl.batch.external.sportsdb.dto.SportsDbEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SportsDbContentMapper {

    private static final String CONTENT_TYPE = "SPORT";
    private static final String EXTERNAL_SOURCE = "THESPORTSDB";

    private static final String SPORT_TAG = "SPORT";
    private static final String LEAGUE_TAG = "LEAGUE";

    public ExternalContentDto fromEvent(SportsDbEvent event) {
        return new ExternalContentDto(
                CONTENT_TYPE,
                event.eventName(),
                null,
                normalizeNullableText(event.thumbnailUrl()),
                EXTERNAL_SOURCE,
                event.id(),
                event.eventDate(),
                null,
                null,
                null,
                createTags(event)
        );
    }

    private List<ExternalContentTagDto> createTags(
            SportsDbEvent event
    ) {
        List<ExternalContentTagDto> tags = new ArrayList<>();

        String sport = normalizeNullableText(event.sport());

        if (sport != null) {
            tags.add(
                    new ExternalContentTagDto(
                            SPORT_TAG,
                            sport
                    )
            );
        }

        String league = normalizeNullableText(
                event.leagueName()
        );

        if (league != null) {
            tags.add(
                    new ExternalContentTagDto(
                            LEAGUE_TAG,
                            league
                    )
            );
        }

        return List.copyOf(tags);
    }

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
