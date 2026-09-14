package com.mopl.playlist.dto;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;

import java.util.Arrays;

public enum PlaylistSortBy {
	UPDATED_AT("updatedAt"),
	SUBSCRIBE_COUNT("subscribeCount");

	private final String value;

	PlaylistSortBy(String value) {
		this.value = value;
	}

	public static PlaylistSortBy from(String value) {
		return Arrays.stream(values())
			.filter(sortBy -> sortBy.value.equalsIgnoreCase(value))
			.findFirst()
			.orElseThrow(() -> new MoplException(CommonErrorCode.INVALID_INPUT_VALUE));
	}
}