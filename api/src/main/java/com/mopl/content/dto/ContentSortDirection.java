package com.mopl.content.dto;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;

public enum ContentSortDirection {

    ASCENDING,
    DESCENDING;

    public static ContentSortDirection from(String value) {
        try {
            return ContentSortDirection.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
