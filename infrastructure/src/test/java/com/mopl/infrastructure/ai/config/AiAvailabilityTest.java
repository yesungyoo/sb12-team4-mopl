package com.mopl.infrastructure.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AiAvailabilityTest {

    @Test
    void isAvailableOnlyWhenEnabledAndApiKeyExists() {
        assertThat(createAvailability(true, "test-api-key").isAvailable())
                .isTrue();
        assertThat(createAvailability(false, "test-api-key").isAvailable())
                .isFalse();
        assertThat(createAvailability(true, " ").isAvailable())
                .isFalse();
        assertThat(createAvailability(true, null).isAvailable())
                .isFalse();
    }

    @Test
    void distinguishesDisabledAndMissingApiKey() {
        assertThat(createAvailability(false, "test-api-key").unavailableReason())
                .isEqualTo(AiAvailability.UnavailableReason.AI_DISABLED);
        assertThat(createAvailability(true, " ").unavailableReason())
                .isEqualTo(AiAvailability.UnavailableReason.API_KEY_MISSING);
    }

    @Test
    void disabledFlagTakesPrecedenceWhenApiKeyIsMissing() {
        AiAvailability withoutApiKey = createAvailability(false, null);
        AiAvailability withBlankApiKey = createAvailability(false, " ");

        assertThat(withoutApiKey.isAvailable()).isFalse();
        assertThat(withoutApiKey.unavailableReason())
                .isEqualTo(AiAvailability.UnavailableReason.AI_DISABLED);

        assertThat(withBlankApiKey.isAvailable()).isFalse();
        assertThat(withBlankApiKey.unavailableReason())
                .isEqualTo(AiAvailability.UnavailableReason.AI_DISABLED);
    }

    private AiAvailability createAvailability(
            boolean enabled,
            String apiKey
    ) {
        AiProperties properties = new AiProperties(
                "https://api.openai.com/v1",
                apiKey,
                "gpt-5.6-luna",
                "text-embedding-3-small",
                Duration.ofSeconds(5),
                Duration.ofSeconds(30)
        );

        return new AiAvailability(properties, enabled);
    }
}
