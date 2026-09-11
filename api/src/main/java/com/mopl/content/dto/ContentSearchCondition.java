package com.mopl.content.dto;

import com.mopl.core.common.enums.ContentType;

import java.time.LocalDate;

public record ContentSearchCondition(
        String keyword,
        ContentType type,
        LocalDate releaseDateFrom,
        LocalDate releaseDateTo
) {
}
