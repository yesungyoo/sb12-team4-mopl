package com.mopl.infrastructure.ai.openai;

import java.util.Locale;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.mopl.infrastructure.ai.client.LlmClient;
import com.mopl.infrastructure.ai.config.AiProperties;
import com.mopl.infrastructure.ai.dto.LlmRequest;
import com.mopl.infrastructure.ai.dto.LlmResponse;
import com.mopl.infrastructure.ai.exception.AiClientException;
import com.mopl.infrastructure.ai.openai.dto.OpenAiLlmRequest;
import com.mopl.infrastructure.ai.openai.dto.OpenAiLlmResponse;

@Component
@RequiredArgsConstructor
public class OpenAiLlmClient implements LlmClient {

    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1000;

    private final OpenAiRestClientFactory restClientFactory;
    private final AiProperties aiProperties;

    @Override
    public LlmResponse generate(LlmRequest request) {
        OpenAiClientValidator.validateApiKey(aiProperties.apiKey());

        // 기능별 maxOutputTokens가 있으면 우선 사용
        int maxOutputTokens = request.maxOutputTokens() == null
                ? DEFAULT_MAX_OUTPUT_TOKENS
                : request.maxOutputTokens();

        // 기능별 reasoning effort
        OpenAiLlmRequest.Reasoning reasoning = request.reasoningEffort() == null
                ? null
                :new OpenAiLlmRequest.Reasoning(
                        request.reasoningEffort()
                                .name()
                                .toLowerCase(Locale.ROOT)
        );

        OpenAiLlmRequest openAiRequest = new OpenAiLlmRequest(
                aiProperties.llmModel(),
                request.systemPrompt(),
                request.userPrompt(),
                reasoning,
                maxOutputTokens
        );

        try {
            OpenAiLlmResponse response = restClientFactory.create()
                    .post()
                    .uri("/responses")
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + aiProperties.apiKey()
                    )
                    .body(openAiRequest)
                    .retrieve()
                    .body(OpenAiLlmResponse.class);

            return new LlmResponse(extractText(response));
        } catch (RestClientException exception) {
            throw new AiClientException(
                    "OpenAI LLM 호출에 실패했습니다.",
                    exception
            );
        }
    }

    private String extractText(OpenAiLlmResponse response) {
        if (response == null || response.output() == null) {
            throw new AiClientException(
                    "OpenAI LLM 응답이 비어 있습니다."
            );
        }

        // 잘린 응답을 정상 텍스트로 넘기지 않는다.
        if (!"completed".equals(response.status())) {
            String reason = response.incompleteDetails() == null
                    ? "unknown"
                    : response.incompleteDetails().reason();

            throw new AiClientException(
                    "OpenAI LLM 응답이 완료되지 않았습니다. "
                    + "status=" + response.status()
                    + ", reason=" + reason
            );
        }

        if (response.output() == null) {
            throw new AiClientException("OpenAI LLM 응답이 비어 있습니다.");
        }

        return response.output().stream()
                .filter(output -> "message".equals(output.type()))
                .filter(output -> output.content() != null)
                .flatMap(output -> output.content().stream())
                .filter(content -> "output_text".equals(content.type()))
                .map(OpenAiLlmResponse.Content::text)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(
                        () -> new AiClientException(
                                "OpenAI LLM 응답에서 텍스트를 찾을 수 없습니다."
                        )
                );
    }
}