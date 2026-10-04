package com.mopl.content.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.mopl.core.common.enums.ContentType;
import java.io.IOException;

public class ContentTypeApiSerializer extends JsonSerializer<ContentType> {

    @Override
    public void serialize(
            ContentType value,
            JsonGenerator generator,
            SerializerProvider serializers
    ) throws IOException {
        generator.writeString(
                switch (value) {
                    case MOVIE -> "movie";
                    case TV_SERIES -> "tvSeries";
                    case SPORT -> "sport";
                }
        );
    }
}
