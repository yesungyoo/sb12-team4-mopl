package com.mopl.realtime.moderation.support;
import com.mopl.realtime.moderation.config.MessageReviewProperties;
import java.time.Duration;
import java.util.List;
public final class MessageReviewTestSettings {
    private MessageReviewTestSettings() {}
    public static MessageReviewProperties defaults() { return withTimeout(Duration.ofSeconds(5)); }
    public static MessageReviewProperties withTimeout(Duration timeout) {
        return new MessageReviewProperties(timeout, 2, 20, List.of("꺼져", "죽어", "멍청이"));
    }
}
