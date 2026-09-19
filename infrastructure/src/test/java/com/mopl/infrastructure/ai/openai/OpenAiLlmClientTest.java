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
import com.mopl.infrastructure.ai.dto.LlmRequest;
import com.mopl.infrastructure.ai.dto.LlmResponse;
import com.mopl.infrastructure.ai.exception.AiClientException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

class OpenAiLlmClientTest {

    private MockRestServiceServer mockServer;
    private OpenAiLlmClient openAiLlmClient;

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

        openAiLlmClient = new OpenAiLlmClient(restClientFactory, aiProperties);
    }

    @Test
    void generateReturnsLlmResponse() {
        String responseBody = """
                {
                  "id": "resp_test",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "추천 결과입니다."
                        }
                      ]
                    }
                  ]
                }
                """;

        mockServer.expect(
                        requestTo("https://api.openai.com/v1/responses")
                )
                .andExpect(method(POST))
                .andRespond(
                        withSuccess(
                                responseBody,
                                MediaType.APPLICATION_JSON
                        )
                );

        LlmResponse response = openAiLlmClient.generate(
                new LlmRequest(
                        "콘텐츠 추천 시스템입니다.",
                        "영화를 추천해 주세요."
                )
        );

        assertThat(response.content())
                .isEqualTo("추천 결과입니다.");

        mockServer.verify();
    }

    @Test
    void generateThrowsExceptionWhenApiKeyIsMissing() {
        AiProperties aiProperties = new AiProperties(
                "https://api.openai.com/v1",
                "",
                "gpt-5.6-luna",
                "text-embedding-3-small",
                Duration.ofSeconds(5),
                Duration.ofSeconds(30)
        );

        OpenAiRestClientFactory restClientFactory = mock(OpenAiRestClientFactory.class);

        OpenAiLlmClient client = new OpenAiLlmClient(
                restClientFactory,
                aiProperties
        );

        LlmRequest request = new LlmRequest(
                "system",
                "user"
        );

        assertThatThrownBy(() -> client.generate(request))
                .isInstanceOf(AiClientException.class)
                .hasMessage("OpenAI API Key가 설정되지 않았습니다.");
    }

    @Test
    void generateThrowsExceptionWhenResponseHasNoText() {
        String responseBody = """
            {
              "id": "resp_test",
              "status": "completed",
              "output": []
            }
            """;

        mockServer.expect(
                        requestTo("https://api.openai.com/v1/responses")
                )
                .andExpect(method(POST))
                .andRespond(
                        withSuccess(
                                responseBody,
                                MediaType.APPLICATION_JSON
                        )
                );

        LlmRequest request = new LlmRequest(
                "system",
                "user"
        );

        assertThatThrownBy(() -> openAiLlmClient.generate(request))
                .isInstanceOf(AiClientException.class)
                .hasMessage(
                        "OpenAI LLM 응답에서 텍스트를 찾을 수 없습니다."
                );

        mockServer.verify();
    }

    @Test
    void generateThrowsExceptionWhenOpenAiReturnsServerError() {
        mockServer.expect(
                        requestTo("https://api.openai.com/v1/responses")
                )
                .andExpect(method(POST))
                .andRespond(withServerError());

        LlmRequest request = new LlmRequest(
                "system",
                "user"
        );

        assertThatThrownBy(() -> openAiLlmClient.generate(request))
                .isInstanceOf(AiClientException.class)
                .hasMessage("OpenAI LLM 호출에 실패했습니다.");

        mockServer.verify();
    }
}
