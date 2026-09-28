package com.mopl.realtime.global.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "websocket")
public record WebSocketProperties(
	List<String> allowedOrigins
) {
}