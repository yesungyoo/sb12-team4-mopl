package com.mopl.infrastructure.ai.openai;

import com.mopl.infrastructure.ai.exception.AiClientException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class OpenAiClientValidator {

    static void validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiClientException("OpenAI API Key가 설정되지 않았습니다.");
        }
    }
}
