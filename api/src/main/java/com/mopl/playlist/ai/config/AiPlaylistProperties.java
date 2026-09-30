package com.mopl.playlist.ai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "mopl.ai")
public record AiPlaylistProperties(
	@DefaultValue("6h")
	Duration candidateTtl
) {

	public AiPlaylistProperties {
		if (candidateTtl == null
			|| candidateTtl.isZero()
			|| candidateTtl.isNegative()) {
			throw new IllegalArgumentException(
				"mopl.ai.candidate-ttl은 0보다 커야 합니다."
			);
		}
	}
}