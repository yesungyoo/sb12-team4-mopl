package com.mopl.batch.external.tmdb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external.tmdb")
public record TmdbProperties(
        String baseUrl,
        String accessToken
) {
}
