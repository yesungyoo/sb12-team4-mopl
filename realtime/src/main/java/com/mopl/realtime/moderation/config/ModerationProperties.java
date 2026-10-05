package com.mopl.realtime.moderation.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mopl.moderation")
public record ModerationProperties(
    @DefaultValue("10m") Duration violationWindow,
    @DefaultValue("3") int violationThreshold,
    @DefaultValue("10") int contextLimit,
    @DefaultValue("5s") Duration reviewTimeout,
    @DefaultValue("1s") Duration applyTimeout,
    @DefaultValue("1m") Duration reviewCooldown,
    @DefaultValue("10m") Duration shortRestriction,
    @DefaultValue("1d") Duration longRestriction,
    @DefaultValue("2") int workers,
    @DefaultValue("20") int queueCapacity,
    @DefaultValue({"씨발", "시발", "씨팔", "개새끼", "병신", "좆", "ㅅㅂ", "ㅂㅅ", "ㅈ같", "ㅈ까", "니애미", "니애비", "느금마", "ㅆㅂ"}) List<String> prohibitedWords
) {
    public ModerationProperties {
        if (violationThreshold < 2 || contextLimit < 1 || contextLimit > 100
            || workers < 1 || queueCapacity < 1 || prohibitedWords == null || prohibitedWords.isEmpty()
            || List.of(violationWindow, reviewTimeout, applyTimeout, reviewCooldown, shortRestriction, longRestriction)
                .stream().anyMatch(value -> value.isNegative() || value.isZero())
            || reviewCooldown.compareTo(reviewTimeout) <= 0
            || longRestriction.compareTo(shortRestriction) < 0) {
            throw new IllegalArgumentException("Invalid moderation settings");
        }
    }
}
