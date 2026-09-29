package com.mopl.watchingsession.dto;

import com.mopl.core.domain.user.entity.User;
import java.util.UUID;

public record WatchingUserSummary(
	UUID userId,
	String name,
	String profileImageUrl
) {

	public static WatchingUserSummary from(User user) {
		return new WatchingUserSummary(
			user.getId(),
			user.getName(),
			user.getProfileImageUrl()
		);
	}
}