package com.mopl.batch.external.sportsdb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external.sportsdb")
public record SportsDbProperties(
        String baseUrl,
        String apiKey
) {
}
