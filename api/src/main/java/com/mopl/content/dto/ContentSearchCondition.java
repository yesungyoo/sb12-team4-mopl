package com.mopl.content.dto;

import com.mopl.core.common.enums.ContentType;

import java.time.LocalDate;
import java.util.List;

public record ContentSearchCondition(
        ContentType typeEqual,
        String keywordLike,
        List<String> tagsIn
) {

    public ContentSearchCondition {
        tagsIn = tagsIn == null
                ? List.of()
                : List.copyOf(tagsIn);
    }
}
