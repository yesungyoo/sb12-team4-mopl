package com.mopl.realtime.moderation.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mopl.moderation.message-review")
public record MessageReviewProperties(
    @DefaultValue("5s") Duration timeout,
    @DefaultValue("2") int workers,
    @DefaultValue("20") int queueCapacity,
    @DefaultValue({"꺼져", "죽어"}) List<String> reviewExpressions
) {
    public MessageReviewProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || workers < 1 || queueCapacity < 1
            || reviewExpressions == null || reviewExpressions.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Invalid message review settings");
        }
        reviewExpressions = List.copyOf(reviewExpressions);
    }
}
