package com.mopl.content.dto;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ContentSortBy {

    CREATED_AT("createdAt", "createdAt"),
    WATCHER_COUNT("watcherCount", "watcherCount"),
    RATE("rate", "averageRating");

    private final String requestValue;
    private final String elasticsearchField;

    public static ContentSortBy from(String value) {
        for (ContentSortBy sortBy : values()) {
            if (sortBy.requestValue.equals(value)) {
                return sortBy;
            }
        }

        throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
    }
}
