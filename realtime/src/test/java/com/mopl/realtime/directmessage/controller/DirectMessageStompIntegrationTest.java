package com.mopl.realtime.directmessage.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.core.common.enums.UserRole;
import com.mopl.infrastructure.security.jwt.JwtProperties;
import com.mopl.infrastructure.security.jwt.JwtTokenProvider;
import com.mopl.infrastructure.security.token.AccessTokenInvalidationService;
import com.mopl.realtime.directmessage.dto.DirectMessageResponse;
import com.mopl.realtime.directmessage.dto.DirectMessageSendRequest;
import com.mopl.realtime.directmessage.repository.ConversationRepository;
import com.mopl.realtime.directmessage.service.DirectMessageService;
import com.mopl.realtime.global.security.WebSocketAuthInterceptor;
import com.mopl.realtime.global.config.WebSocketConfig;
import com.mopl.realtime.contentchat.controller.ContentChatWebSocketController;
import com.mopl.realtime.contentchat.service.ContentChatService;
import com.mopl.realtime.moderation.handler.ModerationErrorHandler;
import com.mopl.realtime.moderation.exception.ModerationException;
import com.mopl.realtime.moderation.dto.SanctionLevel;
import com.mopl.realtime.moderation.repository.ChatRestrictionStore.Restriction;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import java.util.List;
import static org.awaitility.Awaitility.await;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;

@SpringBootTest(
	classes = DirectMessageStompIntegrationTest.TestApplication.class,
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"websocket.allowed-origins[0]=*", "mopl.ai.enabled=false", "spring.ai.model.chat=none"}
)
class DirectMessageStompIntegrationTest {

	@LocalServerPort
	private int port;

	private final UUID senderId = UUID.randomUUID();
	private final UUID conversationId = UUID.randomUUID();

	@org.springframework.beans.factory.annotation.Autowired
	private JwtTokenProvider jwtTokenProvider;

	@org.springframework.beans.factory.annotation.Autowired
	private DirectMessageService directMessageService;

	@org.springframework.beans.factory.annotation.Autowired
	private ConversationRepository conversationRepository;

	@org.springframework.beans.factory.annotation.Autowired
	private AccessTokenInvalidationService accessTokenInvalidationService;

	@org.springframework.beans.factory.annotation.Autowired
	private ObjectMapper objectMapper;

	@org.springframework.beans.factory.annotation.Autowired
	private ContentChatService contentChatService;

	private CompletableFuture<StompHeaders> protocolError;
	@org.springframework.beans.factory.annotation.Autowired
	private SimpUserRegistry users;

	private ThreadPoolTaskScheduler scheduler;
	private WebSocketStompClient stompClient;
	private StompSession stompSession;

	@BeforeEach
	void setUp() throws Exception {
		protocolError = new CompletableFuture<>();
		stompClient = new WebSocketStompClient(
			new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())))
		);

		scheduler = new ThreadPoolTaskScheduler();
		scheduler.initialize();
		stompClient.setTaskScheduler(scheduler);
		org.mockito.Mockito.reset(directMessageService, contentChatService, conversationRepository);

		MappingJackson2MessageConverter messageConverter =
			new MappingJackson2MessageConverter();

		messageConverter.setObjectMapper(objectMapper);
		stompClient.setMessageConverter(messageConverter);

		when(conversationRepository.existsByIdAndParticipantId(
			conversationId,
			senderId
		)).thenReturn(true);

		when(accessTokenInvalidationService.isInvalidated(
			org.mockito.ArgumentMatchers.eq(senderId),
			any(Instant.class)
		)).thenReturn(false);

		String accessToken = jwtTokenProvider.createAccessToken(
			senderId,
			"user@test.com",
			UserRole.USER
		);

		StompHeaders connectHeaders = new StompHeaders();
		connectHeaders.add(
			"Authorization",
			"Bearer " + accessToken
		);

		stompSession = stompClient.connectAsync(
			"ws://localhost:" + port + "/ws",
			new WebSocketHttpHeaders(),
			connectHeaders,
			new StompSessionHandlerAdapter() {
				@Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
				@Override public void handleFrame(StompHeaders headers, Object payload) { protocolError.complete(headers); }
				@Override public void handleException(StompSession session, org.springframework.messaging.simp.stomp.StompCommand command,
					StompHeaders headers, byte[] payload, Throwable exception) {
					protocolError.completeExceptionally(new IllegalStateException(
						"STOMP frame 처리 실패: command=" + command + ", headers=" + headers, exception));
				}
				@Override public void handleTransportError(StompSession session, Throwable exception) {
					protocolError.completeExceptionally(exception);
				}
			}
		).get(5, TimeUnit.SECONDS);
	}

	@AfterEach
	void tearDown() {
		if (stompSession != null && stompSession.isConnected()) {
			try { stompSession.disconnect(); }
			catch (org.springframework.messaging.MessageDeliveryException ignored) {
				// 거부된 STOMP 프레임으로 이미 닫히는 중인 SockJS 세션이다.
			}
		}

		if (scheduler != null) scheduler.shutdown();
		if (stompClient != null) {
			stompClient.stop();
		}
	}

	@Test
	@DisplayName("CONNECT 후 DM 채널을 구독하고 메시지를 전송하면 브로드캐스트된 메시지를 수신한다")
	void connect_subscribe_send_receive() throws Exception {
		UUID messageId = UUID.randomUUID();

		DirectMessageResponse response =
			new DirectMessageResponse(
				messageId,
				conversationId,
				null,
				null,
				null,
				"hello"
			);

		when(directMessageService.send(
			conversationId,
			senderId,
			"hello"
		)).thenReturn(response);

		CompletableFuture<DirectMessageResponse> received =
			new CompletableFuture<>();

		stompSession.subscribe(
			"/sub/conversations/"
				+ conversationId
				+ "/direct-messages",
			new StompFrameHandler() {

				@Override
				public Type getPayloadType(
					StompHeaders headers
				) {
					return DirectMessageResponse.class;
				}

				@Override
				public void handleFrame(
					StompHeaders headers,
					Object payload
				) {
					received.complete(
						(DirectMessageResponse) payload
					);
				}
			}
		);

		waitForSubscription("/sub/conversations/" + conversationId + "/direct-messages");
		stompSession.send(
			"/pub/conversations/"
				+ conversationId
				+ "/direct-messages",
			new DirectMessageSendRequest("hello")
		);

		DirectMessageResponse actual =
			received.get(5, TimeUnit.SECONDS);

		assertThat(actual.id())
			.isEqualTo(messageId);

		assertThat(actual.conversationId())
			.isEqualTo(conversationId);

		assertThat(actual.content())
			.isEqualTo("hello");

		verify(conversationRepository)
			.existsByIdAndParticipantId(
				conversationId,
				senderId
			);

		verify(directMessageService)
			.send(
				conversationId,
				senderId,
				"hello"
			);
	}

	@ParameterizedTest
	@ValueSource(strings = {"chat", "dm"})
	void restrictionDeliveredToOwnErrorSubscription(String channel) throws Exception {
		Instant until = Instant.parse("2030-01-01T00:00:00Z");
		var restriction = new Restriction(SanctionLevel.TEMPORARY_SHORT, until);
		var failure = new ModerationException(ModerationException.ErrorCode.CHAT_RESTRICTED, restriction);
		UUID target = UUID.randomUUID();
		String destination;
		if (channel.equals("chat")) {
			when(contentChatService.send(target, senderId, "hello")).thenThrow(failure);
			destination = "/pub/contents/" + target + "/chat";
		} else {
			when(directMessageService.send(target, senderId, "hello")).thenThrow(failure);
			destination = "/pub/conversations/" + target + "/direct-messages";
		}
		CompletableFuture<JsonNode> received = new CompletableFuture<>();
		stompSession.subscribe("/user/sub/errors", new StompFrameHandler() {
			@Override public Type getPayloadType(StompHeaders headers) { return JsonNode.class; }
			@Override public void handleFrame(StompHeaders headers, Object payload) { received.complete((JsonNode) payload); }
		});
		waitForSubscription("/user/sub/errors");
		stompSession.send(destination, java.util.Map.of("content", "hello"));
		JsonNode error = received.get(5, TimeUnit.SECONDS);
		assertThat(error.path("code").asText()).isEqualTo("CHAT_RESTRICTED");
		assertThat(error.path("restrictionLevel").asText()).isEqualTo("TEMPORARY_SHORT");
		assertThat(Instant.parse(error.path("restrictedUntil").asText())).isEqualTo(until);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/sub/**", "/sub/errors-user*", "/user/another/sub/errors"})
	void forbiddenSubscriptionProducesProtocolError(String destination) throws Exception {
		stompSession.subscribe(destination, new StompSessionHandlerAdapter() {});
		assertThat(protocolError.get(10, TimeUnit.SECONDS).getFirst("message")).isNotBlank();
		org.mockito.Mockito.verifyNoInteractions(directMessageService, contentChatService, conversationRepository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/sub/contents/%s/chat", "/sub/conversations/%s/direct-messages", "/user/sub/errors"})
	void directBrokerSendProducesProtocolError(String destination) throws Exception {
		stompSession.send(destination.formatted(UUID.randomUUID()), java.util.Map.of("content", "forged"));
		assertThat(protocolError.get(10, TimeUnit.SECONDS).getFirst("message")).isNotBlank();
		org.mockito.Mockito.verifyNoInteractions(directMessageService, contentChatService, conversationRepository);
	}

	private void waitForSubscription(String destination) {
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
			assertThat(users.findSubscriptions(subscription -> destination.equals(subscription.getDestination())))
				.isNotEmpty());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(
		exclude = {
			DataSourceAutoConfiguration.class,
			HibernateJpaAutoConfiguration.class,
			JpaRepositoriesAutoConfiguration.class,
			RedisAutoConfiguration.class,
			RedisRepositoriesAutoConfiguration.class,
			KafkaAutoConfiguration.class
		}
	)
	@Import({
		WebSocketConfig.class,
		ModerationErrorHandler.class,
		ContentChatWebSocketController.class,
		WebSocketAuthInterceptor.class,
		DirectMessageWebSocketController.class
	})
	static class TestApplication {

		@Bean
		JwtTokenProvider jwtTokenProvider() {
			JwtProperties jwtProperties =
				new JwtProperties(
					"test-secret-key-test-secret-key-test-secret-key",
					3600L,
					86400L
				);

			return new JwtTokenProvider(jwtProperties);
		}

		@Bean
		AccessTokenInvalidationService accessTokenInvalidationService() {
			return mock(
				AccessTokenInvalidationService.class
			);
		}

		@Bean
		ConversationRepository conversationRepository() {
			return mock(
				ConversationRepository.class
			);
		}

		@Bean
		ContentChatService contentChatService() { return mock(ContentChatService.class); }

		@Bean
		DirectMessageService directMessageService() {
			return mock(
				DirectMessageService.class
			);
		}
	}

}
