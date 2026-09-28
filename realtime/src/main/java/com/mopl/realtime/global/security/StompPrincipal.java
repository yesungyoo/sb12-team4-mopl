package com.mopl.realtime.global.security;

import com.mopl.core.common.enums.UserRole;
import java.security.Principal;
import java.util.UUID;

public record StompPrincipal(
	UUID userId,
	String email,
	UserRole role
) implements Principal {

	@Override
	public String getName() {
		return userId.toString();
	}
}