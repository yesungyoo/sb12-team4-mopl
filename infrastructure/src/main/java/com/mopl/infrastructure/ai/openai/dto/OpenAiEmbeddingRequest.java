package com.mopl.infrastructure.ai.openai.dto;

public record OpenAiEmbeddingRequest(
        String model,
        String input
) {
}
