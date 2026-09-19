package com.mopl.infrastructure.ai.openai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OpenAiLlmRequest(
        String model,
        String instructions,
        String input,

        @JsonProperty("max_output_tokens")
        Integer maxOutputTokens
) {
}
