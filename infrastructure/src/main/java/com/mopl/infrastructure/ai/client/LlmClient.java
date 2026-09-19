package com.mopl.infrastructure.ai.client;

import com.mopl.infrastructure.ai.dto.LlmRequest;
import com.mopl.infrastructure.ai.dto.LlmResponse;

public interface LlmClient {

    LlmResponse generate(LlmRequest request);
}
