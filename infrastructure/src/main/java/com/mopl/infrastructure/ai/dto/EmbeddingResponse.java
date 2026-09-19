package com.mopl.infrastructure.ai.dto;

import java.util.List;

public record EmbeddingResponse(
        List<Double> embedding
) {
}
