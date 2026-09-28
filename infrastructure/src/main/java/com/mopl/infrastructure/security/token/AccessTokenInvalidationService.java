package com.mopl.infrastructure.security.token;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

public class AccessTokenInvalidationService {

	private static final String INVALIDATE_PREFIX = "auth:invalidate:";

	private final StringRedisTemplate redisTemplate;
	private final long accessTokenExpirationSeconds;

	public AccessTokenInvalidationService(
		StringRedisTemplate redisTemplate,
		long accessTokenExpirationSeconds
	) {
		this.redisTemplate = redisTemplate;
		this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
	}

	public void invalidateTokensIssuedBefore(UUID userId, Instant now) {
		redisTemplate.opsForValue().set(
			INVALIDATE_PREFIX + userId,
			String.valueOf(now.toEpochMilli()),
			Duration.ofSeconds(accessTokenExpirationSeconds)
		);
	}

	public boolean isInvalidated(UUID userId, Instant issuedAt) {
		String value = redisTemplate.opsForValue().get(INVALIDATE_PREFIX + userId);

		if (value == null) {
			return false;
		}

		long invalidatedAtEpochMilli = Long.parseLong(value);
		return issuedAt.toEpochMilli() <= invalidatedAtEpochMilli;
	}
}
