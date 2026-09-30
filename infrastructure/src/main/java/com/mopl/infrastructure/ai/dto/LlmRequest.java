package com.mopl.infrastructure.ai.dto;

public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        Integer maxOutputTokens,
        ReasoningEffort reasoningEffort
) {

    public LlmRequest(
            String systemPrompt,
            String userPrompt
    ) {
        this(
                systemPrompt,
                userPrompt,
                null,
                null
        );
    }

    public enum ReasoningEffort {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        XHIGH,
        MAX
    }
}
