package com.mopl.realtime.contentchat;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("e2e")
class ContentChatStompE2ETest {

	private static final Logger log =
		LoggerFactory.getLogger(ContentChatStompE2ETest.class);

	private static final String WS_URL =
		"ws://localhost:8081/ws";

	private static final String CONTENT_ID =
		"11111111-1111-1111-1111-111111111111";

	@ParameterizedTest
	@MethodSource("validContents")
	void validChat_isBroadcast(String content) throws Exception {
		String tokenA = System.getenv("E2E_TOKEN_A");

		assertThat(tokenA)
			.as("E2E_TOKEN_A 환경변수가 필요합니다.")
			.isNotBlank();

		WebSocketStompClient stompClient =
			new WebSocketStompClient(new SockJsClient(java.util.List.of(new WebSocketTransport(new StandardWebSocketClient()))));

		stompClient.setMessageConverter(
			new MappingJackson2MessageConverter()
		);

		StompSession sessionA = connect(stompClient, tokenA);

		String subscribeDestination =
			"/sub/contents/" + CONTENT_ID + "/chat";

		String sendDestination =
			"/pub/contents/" + CONTENT_ID + "/chat";

		CompletableFuture<JsonNode> received =
			new CompletableFuture<>();

		sessionA.subscribe(
			subscribeDestination,
			new StompFrameHandler() {

				@Override
				public @NonNull Type getPayloadType(
					@NonNull StompHeaders headers
				) {
					return JsonNode.class;
				}

				@Override
				public void handleFrame(
					@NonNull StompHeaders headers,
					Object payload
				) {
					JsonNode message = (JsonNode) payload;

					System.out.println(
						"CHAT RECEIVED = " + message
					);

					received.complete(message);
				}
			}
		);

		// SUBSCRIBE가 broker에 등록될 시간을 준다.
		Thread.sleep(500);

		sessionA.send(
			sendDestination,
			Map.of("content", content)
		);

		JsonNode message =
			received.get(5, TimeUnit.SECONDS);

		assertThat(message.path("content").asText())
			.isEqualTo(content);

		sessionA.disconnect();
		stompClient.stop();
	}

	private static Stream<String> validContents() {
		return Stream.of(
			"E2E chat test",
			"a".repeat(1000)
		);
	}

	@ParameterizedTest
	@MethodSource("invalidContents")
	void invalidChat_isNotBroadcast(String content) throws Exception {
		String tokenA = System.getenv("E2E_TOKEN_A");

		assertThat(tokenA)
			.as("E2E_TOKEN_A 환경변수가 필요합니다.")
			.isNotBlank();

		WebSocketStompClient stompClient =
			new WebSocketStompClient(new SockJsClient(java.util.List.of(new WebSocketTransport(new StandardWebSocketClient()))));

		stompClient.setMessageConverter(
			new MappingJackson2MessageConverter()
		);

		StompSession sessionA = connect(stompClient, tokenA);

		String subscribeDestination =
			"/sub/contents/" + CONTENT_ID + "/chat";

		String sendDestination =
			"/pub/contents/" + CONTENT_ID + "/chat";

		CompletableFuture<JsonNode> received =
			new CompletableFuture<>();

		sessionA.subscribe(
			subscribeDestination,
			new StompFrameHandler() {
				@Override
				public @NonNull Type getPayloadType(
					@NonNull StompHeaders headers
				) {
					return JsonNode.class;
				}

				@Override
				public void handleFrame(
					@NonNull StompHeaders headers,
					Object payload
				) {
					received.complete((JsonNode) payload);
				}
			}
		);

		Thread.sleep(500);

		assertThat(sessionA.isConnected()).isTrue();

		sessionA.send(
			sendDestination,
			Map.of("content", content)
		);

		Thread.sleep(1000);

		assertThat(received).isNotCompleted();

		if (sessionA.isConnected()) {
			sessionA.disconnect();
		}

		stompClient.stop();
	}

	@Test
	void twoUsersSubscribeChat_bothReceiveBroadcast() throws Exception {
		String tokenA = System.getenv("E2E_TOKEN_A");
		String tokenB = System.getenv("E2E_TOKEN_B");

		assertThat(tokenA)
			.as("E2E_TOKEN_A 환경변수가 필요합니다.")
			.isNotBlank();

		assertThat(tokenB)
			.as("E2E_TOKEN_B 환경변수가 필요합니다.")
			.isNotBlank();

		WebSocketStompClient stompClient =
			new WebSocketStompClient(new SockJsClient(java.util.List.of(new WebSocketTransport(new StandardWebSocketClient()))));

		stompClient.setMessageConverter(
			new MappingJackson2MessageConverter()
		);

		StompSession sessionA = connect(stompClient, tokenA);
		StompSession sessionB = connect(stompClient, tokenB);

		String subscribeDestination =
			"/sub/contents/" + CONTENT_ID + "/chat";

		String sendDestination =
			"/pub/contents/" + CONTENT_ID + "/chat";

		CompletableFuture<JsonNode> receivedA =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> receivedB =
			new CompletableFuture<>();

		sessionA.subscribe(
			subscribeDestination,
			chatHandler(receivedA)
		);

		sessionB.subscribe(
			subscribeDestination,
			chatHandler(receivedB)
		);

		Thread.sleep(500);

		String content = "E2E two client broadcast test";

		sessionA.send(
			sendDestination,
			Map.of("content", content)
		);

		JsonNode messageA =
			receivedA.get(5, TimeUnit.SECONDS);

		JsonNode messageB =
			receivedB.get(5, TimeUnit.SECONDS);

		assertThat(messageA.path("content").asText())
			.isEqualTo(content);

		assertThat(messageB.path("content").asText())
			.isEqualTo(content);

		sessionA.disconnect();
		sessionB.disconnect();
		stompClient.stop();
	}

	private static Stream<String> invalidContents() {
		return Stream.of(
			"",
			"   ",
			"a".repeat(1001)
		);
	}

	private StompFrameHandler chatHandler(
		CompletableFuture<JsonNode> received
	) {
		return new StompFrameHandler() {
			@Override
			public @NonNull Type getPayloadType(
				@NonNull StompHeaders headers
			) {
				return JsonNode.class;
			}

			@Override
			public void handleFrame(
				@NonNull StompHeaders headers,
				Object payload
			) {
				JsonNode message = (JsonNode) payload;
				System.out.println("CHAT RECEIVED = " + message);
				received.complete(message);
			}
		};
	}

	private StompSession connect(
		WebSocketStompClient stompClient,
		String token
	) throws Exception {

		StompHeaders connectHeaders = new StompHeaders();
		connectHeaders.add(
			"Authorization",
			"Bearer " + token
		);

		return stompClient
			.connectAsync(
				WS_URL,
				new WebSocketHttpHeaders(),
				connectHeaders,
				new StompSessionHandlerAdapter() {

					@Override
					public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
						return String.class;
					}

					@Override
					public void handleFrame(
						@NonNull StompHeaders headers,
						Object payload
					) {
						log.error(
							"STOMP ERROR FRAME headers={}, payload={}",
							headers,
							payload
						);
					}

					@Override
					public void handleException(
						@NonNull StompSession session,
						StompCommand command,
						@NonNull StompHeaders headers,
						@NonNull byte[] payload,
						@NonNull Throwable exception
					) {
						log.error(
							"STOMP exception",
							exception
						);
					}

					@Override
					public void handleTransportError(
						@NonNull StompSession session,
						@NonNull Throwable exception
					) {
						log.error(
							"STOMP transport error",
							exception
						);
					}
				}
			)
			.get(5, TimeUnit.SECONDS);
	}
}