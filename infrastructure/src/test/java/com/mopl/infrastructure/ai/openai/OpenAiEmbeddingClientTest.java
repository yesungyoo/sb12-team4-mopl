package com.mopl.infrastructure.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.mopl.infrastructure.ai.config.AiProperties;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import com.mopl.infrastructure.ai.exception.AiClientException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


import java.time.Duration;

class OpenAiEmbeddingClientTest {

    private MockRestServiceServer mockServer;
    private OpenAiEmbeddingClient openAiEmbeddingClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.openai.com/v1");

        mockServer = MockRestServiceServer
                .bindTo(builder)
                .build();

        AiProperties aiProperties = new AiProperties(
                "https://api.openai.com/v1",
                "test-api-key",
                "gpt-5.6-luna",
                "text-embedding-3-small",
                Duration.ofSeconds(5),
                Duration.ofSeconds(30)
        );

        RestClient restClient = builder.build();

        OpenAiRestClientFactory restClientFactory = mock(OpenAiRestClientFactory.class);

        when(restClientFactory.create()).thenReturn(restClient);

        openAiEmbeddingClient = new OpenAiEmbeddingClient(restClientFactory, aiProperties);
    }

    @Test
    void embedReturnEmbeddingResponse() {
        String responseBody =  """
                {
                  "data": [
                    {
                      "index": 0,
                      "embedding": [
                        0.12,
                        -0.34,
                        0.56
                      ]
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/embeddings"))
                .andExpect(method(POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        EmbeddingResponse response = openAiEmbeddingClient.embed(
                new EmbeddingRequest("인터스텔라")
        );

        assertThat(response.embedding())
                .containsExactly(0.12, -0.34, 0.56);

        mockServer.verify();
    }

    @Test
    void embedThrowsExceptionWhenApiKeyIsMissing() {
        AiProperties aiProperties = new AiProperties(
                "https://api.openai.com/v1",
                "",
                "gpt-5.6-luna",
                "text-embedding-3-small",
                Duration.ofSeconds(5),
                Duration.ofSeconds(30)
        );

        OpenAiRestClientFactory restClientFactory = mock(OpenAiRestClientFactory.class);

        OpenAiEmbeddingClient client =
                new OpenAiEmbeddingClient(
                        restClientFactory,
                        aiProperties
                );

        EmbeddingRequest request = new EmbeddingRequest("인터스텔라");

        assertThatThrownBy(() -> client.embed(request))
                .isInstanceOf(AiClientException.class)
                .hasMessage("OpenAI API Key가 설정되지 않았습니다.");
    }

    @Test
    void embedThrowsExceptionWhenResponseHasNoEmbedding() {
        String responseBody = """
            {
              "data": []
            }
            """;

        mockServer.expect(
                        requestTo("https://api.openai.com/v1/embeddings")
                )
                .andExpect(method(POST))
                .andRespond(
                        withSuccess(
                                responseBody,
                                MediaType.APPLICATION_JSON
                        )
                );

        EmbeddingRequest request = new EmbeddingRequest(
                "인터스텔라"
        );

        assertThatThrownBy(() -> openAiEmbeddingClient.embed(request))
                .isInstanceOf(AiClientException.class)
                .hasMessage(
                        "OpenAI Embedding 응답이 비어 있습니다."
                );

        mockServer.verify();
    }

    @Test
    void embedThrowsExceptionWhenOpenAiReturnsServerError() {
        mockServer.expect(
                        requestTo("https://api.openai.com/v1/embeddings")
                )
                .andExpect(method(POST))
                .andRespond(withServerError());

        EmbeddingRequest request = new EmbeddingRequest(
                "인터스텔라"
        );

        assertThatThrownBy(() -> openAiEmbeddingClient.embed(request))
                .isInstanceOf(AiClientException.class)
                .hasMessage("OpenAI Embedding 호출에 실패했습니다.");

        mockServer.verify();
    }
}
