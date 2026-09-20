package com.mopl.message.dto;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;

import java.util.Arrays;

public enum DirectMessageSortBy {
	CREATED_AT("createdAt");

	private final String value;

	DirectMessageSortBy(String value) {
		this.value = value;
	}

	public static DirectMessageSortBy from(String value) {
		return Arrays.stream(values())
			.filter(sortBy -> sortBy.value.equalsIgnoreCase(value))
			.findFirst()
			.orElseThrow(() -> new MoplException(CommonErrorCode.INVALID_INPUT_VALUE));
	}
}