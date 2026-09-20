package com.mopl.message.dto;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;

public enum SortDirection {
	ASCENDING,
	DESCENDING;

	public static SortDirection from(String value) {
		try {
			return SortDirection.valueOf(value.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
	}
}