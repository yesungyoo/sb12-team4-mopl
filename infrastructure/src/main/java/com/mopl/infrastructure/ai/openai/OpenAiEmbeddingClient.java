package com.mopl.infrastructure.ai.openai;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.config.AiProperties;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import com.mopl.infrastructure.ai.exception.AiClientException;
import com.mopl.infrastructure.ai.openai.dto.OpenAiEmbeddingRequest;
import com.mopl.infrastructure.ai.openai.dto.OpenAiEmbeddingResponse;

@Component
@RequiredArgsConstructor
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final OpenAiRestClientFactory restClientFactory;
    private final AiProperties aiProperties;

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        OpenAiClientValidator.validateApiKey(aiProperties.apiKey());

        OpenAiEmbeddingRequest openAiRequest =
                new OpenAiEmbeddingRequest(
                        aiProperties.embeddingModel(),
                        request.input()
                );

        try {
            OpenAiEmbeddingResponse response = restClientFactory.create()
                    .post()
                    .uri("/embeddings")
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + aiProperties.apiKey()
                    )
                    .body(openAiRequest)
                    .retrieve()
                    .body(OpenAiEmbeddingResponse.class);

            return new EmbeddingResponse(
                    extractEmbedding(response)
            );
        } catch (RestClientException exception) {
            throw new AiClientException(
                    "OpenAI Embedding 호출에 실패했습니다.",
                    exception
            );
        }
    }

    private List<Double> extractEmbedding(
            OpenAiEmbeddingResponse response
    ) {
        if (response == null
                || response.data() == null
                || response.data().isEmpty()
                || response.data().get(0).embedding() == null) {
            throw new AiClientException(
                    "OpenAI Embedding 응답이 비어 있습니다."
            );
        }

        return response.data()
                .get(0)
                .embedding();
    }
}