package com.mopl.realtime.moderation.review;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.core.common.enums.MessageType;
import com.mopl.realtime.moderation.dto.SanctionLevel;
import com.mopl.realtime.moderation.service.ModerationContextService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class SpringAiModerationReviewerTest {
    @ParameterizedTest @EnumSource(SanctionLevel.class)
    void modelFunctionCallSubmitsDecisionWithoutApplyingRedis(SanctionLevel level) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String arguments = mapper.writeValueAsString(java.util.Map.of("level", level.name(), "reason", "반복 위반"));
            String body = mapper.writeValueAsString(java.util.Map.of(
                "id", "test", "object", "chat.completion", "created", 1, "model", "test",
                "choices", List.of(java.util.Map.of("index", 0, "finish_reason", "tool_calls", "message",
                    java.util.Map.of("role", "assistant", "tool_calls", List.of(java.util.Map.of(
                        "id", "call_1", "type", "function", "function",
                        java.util.Map.of("name", "applyChatRestriction", "arguments", arguments))))))));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var api = OpenAiApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-key").build();
            ChatClient client = ChatClient.create(OpenAiChatModel.builder().openAiApi(api).build());
            var provider = new StaticListableBeanFactory(java.util.Map.of("client", client)).getBeanProvider(ChatClient.class);
            var reviewer = new SpringAiModerationReviewer(provider, mapper);
            UUID user = UUID.randomUUID();
            var deadline = Instant.now().plusSeconds(10);
            var observation = new ReviewObservation(deadline);
            var inbox = new SanctionDecisionInbox(deadline, observation);
            var tool = new SanctionTool(inbox, observation);

            reviewer.review(new ModerationContextService.ReviewContext(user, MessageType.DM, "씨발", List.of(), List.of()), tool);

            assertThat(inbox.await().level()).isEqualTo(level);
            assertThat(inbox.state()).isEqualTo(SanctionDecisionInbox.State.ACCEPTED);
            var schema = mapper.readTree(request.get()).path("tools").get(0).path("function").path("parameters");
            assertThat(schema.path("properties").has("userId")).isFalse();
            assertThat(schema.path("properties").has("token")).isFalse();
            assertThat(schema.path("properties").path("level").path("enum").toString())
                .contains("NONE", "TEMPORARY_SHORT", "TEMPORARY_LONG");
        } finally { server.stop(0); }
    }
}
