package com.mopl.batch.external.sportsdb.mapper;

import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.sportsdb.dto.SportsDbEvent;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SportsDbContentMapper {

    private static final String CONTENT_TYPE = "SPORTS";
    private static final String EXTERNAL_SOURCE = "THESPORTSDB";

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
                List.of()
        );
    }

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value;
    }
}
