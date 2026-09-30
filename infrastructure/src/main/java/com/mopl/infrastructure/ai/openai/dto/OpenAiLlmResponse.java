package com.mopl.infrastructure.ai.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;


import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiLlmResponse(
        String id,
        String status,

        @JsonProperty("incomplete_details")
        IncompleteDetails incompleteDetails,

        List<Output> output
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IncompleteDetails(
            String reason
    ) {}

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
