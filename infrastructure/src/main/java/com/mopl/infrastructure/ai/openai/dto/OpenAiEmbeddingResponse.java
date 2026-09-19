package com.mopl.infrastructure.ai.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiEmbeddingResponse(
        List<Data> data
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            Integer index,
            List<Double> embedding
    ) {

    }
}
