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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;

@SpringBootTest(
	classes = DirectMessageStompIntegrationTest.TestApplication.class,
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
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

	private WebSocketStompClient stompClient;
	private StompSession stompSession;

	@BeforeEach
	void setUp() throws Exception {
		stompClient = new WebSocketStompClient(
			new StandardWebSocketClient()
		);

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
			}
		).get(5, TimeUnit.SECONDS);
	}

	@AfterEach
	void tearDown() {
		if (stompSession != null && stompSession.isConnected()) {
			stompSession.disconnect();
		}

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
		TestWebSocketConfig.class,
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
		DirectMessageService directMessageService() {
			return mock(
				DirectMessageService.class
			);
		}
	}

	@Configuration
	@EnableWebSocketMessageBroker
	static class TestWebSocketConfig
		implements WebSocketMessageBrokerConfigurer {

		private final WebSocketAuthInterceptor
			webSocketAuthInterceptor;

		TestWebSocketConfig(
			WebSocketAuthInterceptor
				webSocketAuthInterceptor
		) {
			this.webSocketAuthInterceptor =
				webSocketAuthInterceptor;
		}

		@Override
		public void registerStompEndpoints(
			StompEndpointRegistry registry
		) {
			registry.addEndpoint("/ws")
				.setAllowedOriginPatterns("*");
		}

		@Override
		public void configureMessageBroker(
			MessageBrokerRegistry registry
		) {
			registry.setApplicationDestinationPrefixes(
				"/pub"
			);
			registry.enableSimpleBroker("/sub");
		}

		@Override
		public void configureClientInboundChannel(
			ChannelRegistration registration
		) {
			registration.interceptors(
				webSocketAuthInterceptor
			);
		}
	}
}