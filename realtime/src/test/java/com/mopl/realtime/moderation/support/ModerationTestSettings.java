package com.mopl.realtime.moderation.support;

import com.mopl.realtime.moderation.config.ModerationProperties;
import java.time.Duration;
import java.util.List;

public final class ModerationTestSettings {
    public static ModerationProperties defaults() { return withTimeout(Duration.ofSeconds(2)); }
    public static ModerationProperties withTimeout(Duration timeout) {
        return new ModerationProperties(Duration.ofMinutes(10), 3, 10, timeout, Duration.ofSeconds(1), Duration.ofMinutes(1),
            Duration.ofMinutes(10), Duration.ofDays(1), 2, 20,
            List.of("씨발", "시발", "씨팔", "개새끼", "병신", "좆", "ㅅㅂ", "ㅂㅅ", "ㅈ같", "ㅈ까", "니애미", "니애비", "느금마", "ㅆㅂ"));
    }
}
