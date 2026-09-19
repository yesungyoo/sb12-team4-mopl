package com.mopl.infrastructure.ai.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiLlmResponse(
        String id,
        String status,
        List<Output> output
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            String type,
            List<Content> content
    ) {

    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(
            String type,
            String text
    ) {

    }
}
