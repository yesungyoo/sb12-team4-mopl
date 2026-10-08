package com.mopl.infrastructure.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class AiAvailability {

    private final AiProperties aiProperties;
    private final boolean aiEnabled;

    public AiAvailability(
            AiProperties aiProperties,
            @Value("${mopl.ai.enabled:false}")
            boolean aiEnabled
    ) {
        this.aiProperties = aiProperties;
        this.aiEnabled = aiEnabled;
    }

    @PostConstruct
    void logAvailability() {
        UnavailableReason unavailableReason = unavailableReason();

        if (unavailableReason != null) {
            log.info("AI 기능이 비활성 상태입니다. reason={}", unavailableReason);
        }
    }

    public boolean isAvailable() {
        return unavailableReason() == null;
    }

    public UnavailableReason unavailableReason() {
        if (!aiEnabled) {
            return UnavailableReason.AI_DISABLED;
        }

        if (!StringUtils.hasText(aiProperties.apiKey())) {
            return UnavailableReason.API_KEY_MISSING;
        }

        return null;
    }

    public enum UnavailableReason {
        AI_DISABLED,
        API_KEY_MISSING
    }
}
