package com.mopl.realtime.common.dto;

import com.mopl.core.domain.user.entity.User;
import java.util.UUID;

public record UserSummary(
	UUID userId,
	String name,
	String profileImageUrl
) {

	public static UserSummary from(User user) {
		return new UserSummary(
			user.getId(),
			user.getName(),
			user.getProfileImageUrl()
		);
	}
}