package com.mopl.realtime.watchingsession;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.junit.jupiter.api.Test;
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
import org.junit.jupiter.api.Tag;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("e2e")
class WatchingSessionStompE2ETest {

	private static final Logger log =
		LoggerFactory.getLogger(WatchingSessionStompE2ETest.class);

	private static final String WS_URL = "ws://localhost:8081/ws";

	private static final String CONTENT_ID =
		"11111111-1111-1111-1111-111111111111";

	private static final String CONTENT_ID_2 =
		"22222222-2222-2222-2222-222222222222";

	@Test
	void twoUsersSubscribeWatch_thenOneUnsubscribes_broadcastsLeave() throws Exception {
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

		String destination =
			"/sub/contents/" + CONTENT_ID + "/watch";

		CompletableFuture<JsonNode> receivedByA =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> leaveReceivedByB =
			new CompletableFuture<>();

		// A가 먼저 watch를 구독한다.
		StompSession.Subscription subscriptionA =
			sessionA.subscribe(destination, new StompFrameHandler() {

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

				System.out.println("A RECEIVED = " + message);

				if ("JOIN".equals(message.path("type").asText())
					&& message.path("watcherCount").asLong() == 2) {
					receivedByA.complete(message);
				}
			}
		});

		// A의 SUBSCRIBE가 broker에 등록될 시간을 준다.
		Thread.sleep(500);

		// B가 같은 콘텐츠를 구독한다.
		sessionB.subscribe(destination, new StompFrameHandler() {

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

				System.out.println("B RECEIVED = " + message);

				if ("LEAVE".equals(message.path("type").asText())
					&& message.path("watcherCount").asLong() == 1) {
					leaveReceivedByB.complete(message);
				}
			}
		});

		JsonNode message =
			receivedByA.get(5, TimeUnit.SECONDS);

		assertThat(message.path("type").asText())
			.isEqualTo("JOIN");

		assertThat(message.path("watcherCount").asLong())
			.isEqualTo(2);

		// A만 watch 구독을 해제한다.
		subscriptionA.unsubscribe();

		JsonNode leaveMessage =
			leaveReceivedByB.get(5, TimeUnit.SECONDS);

		assertThat(leaveMessage.path("type").asText())
			.isEqualTo("LEAVE");

		assertThat(leaveMessage.path("watcherCount").asLong())
			.isEqualTo(1);

		sessionA.disconnect();
		sessionB.disconnect();
		stompClient.stop();
	}

	@Test
	void twoUsersSubscribeWatch_thenOneDisconnects_broadcastsLeave() throws Exception {
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

		String destination =
			"/sub/contents/" + CONTENT_ID + "/watch";

		CompletableFuture<JsonNode> joinReceivedByA =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> leaveReceivedByA =
			new CompletableFuture<>();

		// A가 먼저 watch를 구독한다.
		sessionA.subscribe(destination, new StompFrameHandler() {
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

				System.out.println("A RECEIVED = " + message);

				if ("JOIN".equals(message.path("type").asText())
					&& message.path("watcherCount").asLong() == 2) {
					joinReceivedByA.complete(message);
				}

				if ("LEAVE".equals(message.path("type").asText())
					&& message.path("watcherCount").asLong() == 1) {
					leaveReceivedByA.complete(message);
				}
			}
		});

		Thread.sleep(500);

		// B가 같은 콘텐츠를 구독한다.
		sessionB.subscribe(destination, new StompFrameHandler() {
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
				System.out.println("B RECEIVED = " + payload);
			}
		});

		// B의 JOIN이 실제 처리되어 watcherCount=2가 된 것을 먼저 확인한다.
		JsonNode joinMessage =
			joinReceivedByA.get(5, TimeUnit.SECONDS);

		assertThat(joinMessage.path("type").asText())
			.isEqualTo("JOIN");

		assertThat(joinMessage.path("watcherCount").asLong())
			.isEqualTo(2);

		// UNSUBSCRIBE 없이 B의 WebSocket 연결 자체를 종료한다.
		sessionB.disconnect();

		JsonNode leaveMessage =
			leaveReceivedByA.get(5, TimeUnit.SECONDS);

		assertThat(leaveMessage.path("type").asText())
			.isEqualTo("LEAVE");

		assertThat(leaveMessage.path("watcherCount").asLong())
			.isEqualTo(1);

		sessionA.disconnect();
		stompClient.stop();
	}

	@Test
	void sameUserResubscribesSameContent_broadcastsJoinWithoutLeave() throws Exception {
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

		String destination =
			"/sub/contents/" + CONTENT_ID + "/watch";

		CompletableFuture<JsonNode> firstJoin =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> secondJoin =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> leaveReceived =
			new CompletableFuture<>();

		StompFrameHandler handler =
			sameContentResubscribeHandler(
				firstJoin,
				secondJoin,
				leaveReceived
			);

		// 첫 번째 구독
		StompSession.Subscription firstSubscription =
			sessionA.subscribe(destination, handler);

		JsonNode firstJoinMessage =
			firstJoin.get(5, TimeUnit.SECONDS);

		assertThat(firstJoinMessage.path("type").asText())
			.isEqualTo("JOIN");

		assertThat(firstJoinMessage.path("watcherCount").asLong())
			.isEqualTo(1);

		// 같은 WebSocket에서 같은 콘텐츠를 두 번째로 구독
		StompSession.Subscription secondSubscription =
			sessionA.subscribe(destination, handler);

		JsonNode secondJoinMessage =
			secondJoin.get(5, TimeUnit.SECONDS);

		assertThat(secondJoinMessage.path("type").asText())
			.isEqualTo("JOIN");

		assertThat(secondJoinMessage.path("watcherCount").asLong())
			.isEqualTo(1);

		// 같은 콘텐츠 재구독이므로 중간 LEAVE가 발생하면 안 된다.
		Thread.sleep(1000);

		assertThat(leaveReceived).isNotCompleted();

		// 두 번째 구독만 해제한다.
		// 첫 번째 구독은 아직 STOMP broker에 남아 있으므로
		// 시청 세션 전체가 LEAVE 처리되면 안 된다.
		secondSubscription.unsubscribe();

		Thread.sleep(1000);

		assertThat(leaveReceived)
			.as("첫 번째 watch 구독이 남아 있으므로 LEAVE가 발생하면 안 됩니다.")
			.isNotCompleted();

		firstSubscription.unsubscribe();

		sessionA.disconnect();
		stompClient.stop();
	}

	@Test
	void sameUserLeavesAndReentersSameContent_broadcastsLeaveThenJoin() throws Exception {
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

		String destination =
			"/sub/contents/" + CONTENT_ID + "/watch";

		CompletableFuture<JsonNode> firstJoin =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> leave =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> rejoin =
			new CompletableFuture<>();

		// B는 계속 같은 콘텐츠를 보고 있으면서 A의 입장/퇴장/재입장을 관찰한다.
		sessionB.subscribe(
			destination,
			reentryObserver(firstJoin, leave, rejoin)
		);

		Thread.sleep(500);

		// A 첫 입장
		StompSession.Subscription subscriptionA =
			sessionA.subscribe(destination, jsonFrameHandler());

		JsonNode firstJoinMessage =
			firstJoin.get(5, TimeUnit.SECONDS);

		assertThat(firstJoinMessage.path("type").asText())
			.isEqualTo("JOIN");

		assertThat(firstJoinMessage.path("watcherCount").asLong())
			.isEqualTo(2);

		// A가 콘텐츠에서 나감
		subscriptionA.unsubscribe();

		JsonNode leaveMessage =
			leave.get(5, TimeUnit.SECONDS);

		assertThat(leaveMessage.path("type").asText())
			.isEqualTo("LEAVE");

		assertThat(leaveMessage.path("watcherCount").asLong())
			.isEqualTo(1);

		// A가 같은 콘텐츠에 다시 입장
		sessionA.subscribe(destination, jsonFrameHandler());

		JsonNode rejoinMessage =
			rejoin.get(5, TimeUnit.SECONDS);

		assertThat(rejoinMessage.path("type").asText())
			.isEqualTo("JOIN");

		assertThat(rejoinMessage.path("watcherCount").asLong())
			.isEqualTo(2);

		sessionA.disconnect();
		sessionB.disconnect();
		stompClient.stop();
	}

	@Test
	void sameUserSubscribesDifferentContent_replacesPreviousWatchingSession()
		throws Exception {
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

		String content1Destination =
			"/sub/contents/" + CONTENT_ID + "/watch";

		String content2Destination =
			"/sub/contents/" + CONTENT_ID_2 + "/watch";

		CompletableFuture<JsonNode> content1Join =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> content1Leave =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> content2Join =
			new CompletableFuture<>();

		CompletableFuture<JsonNode> staleContent1Event =
			new CompletableFuture<>();

		StompSession.Subscription content1SubscriptionB =
			sessionB.subscribe(
				content1Destination,
				contentChangeObserver(
					content1Join,
					content1Leave
				)
			);

		Thread.sleep(500);

		sessionA.subscribe(
			content1Destination,
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

					if ("LEAVE".equals(
						message.path("type").asText()
					)
						&& message.path("watcherCount").asLong() == 0) {
						staleContent1Event.complete(message);
					}
				}
			}
		);

		JsonNode firstJoinMessage =
			content1Join.get(5, TimeUnit.SECONDS);

		assertThat(firstJoinMessage.path("type").asText())
			.isEqualTo("JOIN");

		assertThat(firstJoinMessage.path("watcherCount").asLong())
			.isEqualTo(2);

		sessionA.subscribe(
			content2Destination,
			joinObserver(content2Join)
		);

		JsonNode leaveMessage =
			content1Leave.get(5, TimeUnit.SECONDS);

		assertThat(leaveMessage.path("type").asText())
			.isEqualTo("LEAVE");

		assertThat(leaveMessage.path("watcherCount").asLong())
			.isEqualTo(1);

		JsonNode secondJoinMessage =
			content2Join.get(5, TimeUnit.SECONDS);

		assertThat(secondJoinMessage.path("type").asText())
			.isEqualTo("JOIN");

		assertThat(secondJoinMessage.path("watcherCount").asLong())
			.isEqualTo(1);

		content1SubscriptionB.unsubscribe();

		Thread.sleep(1000);

		assertThat(staleContent1Event)
			.as(
				"content2로 전환한 사용자는 "
					+ "이전 content1 이벤트를 받으면 안 됩니다."
			)
			.isNotCompleted();

		sessionA.disconnect();
		sessionB.disconnect();
		stompClient.stop();
	}

	private StompFrameHandler sameContentResubscribeHandler(
		CompletableFuture<JsonNode> firstJoin,
		CompletableFuture<JsonNode> secondJoin,
		CompletableFuture<JsonNode> leaveReceived
	) {
		AtomicInteger joinCount = new AtomicInteger();

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

				System.out.println("A RECEIVED = " + message);

				if ("JOIN".equals(message.path("type").asText())) {
					int count = joinCount.incrementAndGet();

					if (count == 1) {
						firstJoin.complete(message);
					} else if (count == 2) {
						secondJoin.complete(message);
					}
				}

				if ("LEAVE".equals(message.path("type").asText())) {
					leaveReceived.complete(message);
				}
			}
		};
	}

	private StompFrameHandler reentryObserver(
		CompletableFuture<JsonNode> firstJoin,
		CompletableFuture<JsonNode> leave,
		CompletableFuture<JsonNode> rejoin
	) {
		AtomicInteger joinCount = new AtomicInteger();

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

				System.out.println("B RECEIVED = " + message);

				if ("JOIN".equals(message.path("type").asText())
					&& message.path("watcherCount").asLong() == 2) {

					int count = joinCount.incrementAndGet();

					if (count == 1) {
						firstJoin.complete(message);
					} else if (count == 2) {
						rejoin.complete(message);
					}
				}

				if ("LEAVE".equals(message.path("type").asText())
					&& message.path("watcherCount").asLong() == 1) {
					leave.complete(message);
				}
			}
		};
	}

	private StompFrameHandler jsonFrameHandler() {
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
				// 이 테스트에서는 수신 메시지를 별도로 검증하지 않는다.
			}
		};
	}

	private StompFrameHandler contentChangeObserver(
		CompletableFuture<JsonNode> join,
		CompletableFuture<JsonNode> leave
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

				System.out.println("CONTENT 1 RECEIVED = " + message);

				if ("JOIN".equals(message.path("type").asText())
					&& message.path("watcherCount").asLong() == 2) {
					join.complete(message);
				}

				if ("LEAVE".equals(message.path("type").asText())
					&& message.path("watcherCount").asLong() == 1) {
					leave.complete(message);
				}
			}
		};
	}

	private StompFrameHandler joinObserver(
		CompletableFuture<JsonNode> join
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

				System.out.println("CONTENT 2 RECEIVED = " + message);

				if ("JOIN".equals(message.path("type").asText())
					&& message.path("watcherCount").asLong() == 1) {
					join.complete(message);
				}
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
					public void handleException(
						@NonNull StompSession session,
						StompCommand command,
						@NonNull StompHeaders headers,
						@NonNull byte[] payload,
						@NonNull Throwable exception
					) {
						log.error("STOMP exception", exception);
					}

					@Override
					public void handleTransportError(
						@NonNull StompSession session,
						@NonNull Throwable exception
					) {
						log.error("STOMP transport error", exception);
					}
				}
			)
			.get(5, TimeUnit.SECONDS);
	}
}