package com.mopl.infrastructure.ai.client;

import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;

public interface EmbeddingClient {

    EmbeddingResponse embed(EmbeddingRequest request);
}
