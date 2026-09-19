package com.mopl.infrastructure.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "ai.openai")
public record AiProperties(
        @DefaultValue("https://api.openai.com/v1")
        String baseUrl,

        String apiKey,

        @DefaultValue("gpt-5.6-luna")
        String llmModel,

        @DefaultValue("text-embedding-3-small")
        String embeddingModel,

        @DefaultValue("5s")
        Duration connectTimeout,

        @DefaultValue("30s")
        Duration readTimeout
) {
}