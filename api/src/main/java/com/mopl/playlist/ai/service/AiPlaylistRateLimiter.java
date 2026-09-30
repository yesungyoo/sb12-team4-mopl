package com.mopl.playlist.ai.service;

import com.mopl.common.exception.playlist.PlaylistAiRateLimitExceededException;
import com.mopl.common.exception.playlist.PlaylistAiRateLimitStoreUnavailableException;
import com.mopl.playlist.ai.config.AiPlaylistRateLimitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AiPlaylistRateLimiter {

	private static final String KEY_PREFIX = "ai:playlist:rate-limit:";

	private static final DefaultRedisScript<String> RATE_LIMIT_SCRIPT =
		new DefaultRedisScript<>("""
                    local current = redis.call('INCR', KEYS[1])

                    if current == 1 then
                        redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    end

                    local ttl = redis.call('PTTL', KEYS[1])

                    return tostring(current) .. ':' .. tostring(ttl)
                    """, String.class);

	private final StringRedisTemplate redisTemplate;
	private final AiPlaylistRateLimitProperties properties;

	public void check(UUID userId) {
		String key = buildKey(userId);

		try {
			String result = redisTemplate.execute(
				RATE_LIMIT_SCRIPT,
				List.of(key),
				String.valueOf(properties.window().toMillis())
			);

			RateLimitResult rateLimitResult = parseResult(result);

			if (rateLimitResult.current() > properties.maxRequests()) {
				throw new PlaylistAiRateLimitExceededException(
					toRetryAfterSeconds(rateLimitResult.ttlMillis())
				);
			}
		} catch (DataAccessException e) {
			throw new PlaylistAiRateLimitStoreUnavailableException();
		}
	}

	private RateLimitResult parseResult(String result) {
		try {
			String[] values = result.split(":");

			if (values.length != 2) {
				throw new IllegalArgumentException();
			}

			return new RateLimitResult(
				Long.parseLong(values[0]),
				Long.parseLong(values[1])
			);
		} catch (IllegalArgumentException e) {
			throw new PlaylistAiRateLimitStoreUnavailableException();
		}
	}

	private long toRetryAfterSeconds(long ttlMillis) {
		if (ttlMillis <= 0) {
			return 1;
		}

		return Math.max(1, (ttlMillis + 999) / 1000);
	}

	private String buildKey(UUID userId) {
		return KEY_PREFIX + userId;
	}

	private record RateLimitResult(
		long current,
		long ttlMillis
	) {
	}
}