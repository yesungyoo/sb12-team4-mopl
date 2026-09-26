package com.mopl.content.converter;

import com.mopl.core.common.enums.ContentType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class ContentTypeConverter implements Converter<String, ContentType> {

    @Override
    public ContentType convert(String source) {
        return switch (source) {
            case "movie", "MOVIE" -> ContentType.MOVIE;
            case "tvSeries", "TV_SERIES" -> ContentType.TV_SERIES;
            case "sport", "SPORT" -> ContentType.SPORT;

            default -> throw new IllegalArgumentException(
                    "지원하지 않는 콘텐츠 타입입니다: " + source
            );
        };
    }
}
