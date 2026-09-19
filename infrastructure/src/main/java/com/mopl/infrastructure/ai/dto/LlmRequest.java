package com.mopl.infrastructure.ai.dto;

public record LlmRequest(
        String systemPrompt,
        String userPrompt
) {
}
