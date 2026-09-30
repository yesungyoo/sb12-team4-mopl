package com.mopl.playlist.ai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "mopl.ai.rate-limit")
public record AiPlaylistRateLimitProperties(
	@DefaultValue("10")
	int maxRequests,

	@DefaultValue("1m")
	Duration window
) {

	public AiPlaylistRateLimitProperties {
		if (maxRequests <= 0) {
			throw new IllegalArgumentException(
				"mopl.ai.rate-limit.max-requests는 0보다 커야 합니다."
			);
		}

		if (window == null || window.isZero() || window.isNegative()) {
			throw new IllegalArgumentException(
				"mopl.ai.rate-limit.window은 0보다 커야 합니다."
			);
		}
	}
}