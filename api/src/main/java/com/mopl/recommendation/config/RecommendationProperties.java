package com.mopl.recommendation.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "recommendation")
public record RecommendationProperties(
        @DefaultValue("30")
        int candidateSize,

        @DefaultValue("10")
        int resultSize,

        @DefaultValue("6h")
        Duration cacheTtl
) {

    public RecommendationProperties {
        if (candidateSize <= 0) {
            throw new IllegalArgumentException("recommendation.candidate-size는 1 이상이어야 합니다.");
        }

        if (resultSize <= 0) {
            throw new IllegalArgumentException("recommendation.result-size는 1 이상이어야 합니다.");
        }

        if (resultSize > candidateSize) {
            throw new IllegalArgumentException("recommendation.result.size는 candidate-size보다 클 수 없습니다.");
        }

        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("recommendation.cache-ttl은 0보다 커야 합니다.");
        }
    }
}
