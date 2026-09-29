package com.mopl.realtime.watchingsession.listener;

import static org.mockito.Mockito.*;

import com.mopl.core.domain.watchingsession.model.WatchingSessionState;
import com.mopl.realtime.watchingsession.dto.ChangeType;
import com.mopl.realtime.watchingsession.dto.WatchingSessionChange;
import com.mopl.realtime.watchingsession.dto.WatchingSessionDto;
import com.mopl.realtime.watchingsession.service.WatchingSessionJoinResult;
import com.mopl.realtime.watchingsession.service.WatchingSessionService;
import com.mopl.realtime.watchingsession.service.WatchingSubscriptionBroker;
import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@ExtendWith(MockitoExtension.class)
class WatchingSessionEventListenerTest {

	@Mock
	private WatchingSessionService watchingSessionService;

	@Mock
	private SimpMessagingTemplate messagingTemplate;

	@Mock
	private WatchingSubscriptionBroker watchingSubscriptionBroker;

	@Mock
	private Principal principal;

	private WatchingSessionEventListener listener;

	@BeforeEach
	void setUp() {
		listener = new WatchingSessionEventListener(
			watchingSessionService,
			messagingTemplate,
			watchingSubscriptionBroker
		);
	}

	@Nested
	@DisplayName("시청 구독")
	class Subscribe {

		@Test
		@DisplayName("시청 destination을 구독하면 시청 세션에 참여한다")
		void success() {
			UUID userId = UUID.randomUUID();
			UUID contentId = UUID.randomUUID();

			when(principal.getName()).thenReturn(userId.toString());

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.SUBSCRIBE);

			accessor.setDestination(
				"/sub/contents/" + contentId + "/watch"
			);
			accessor.setSessionId("ws-1");
			accessor.setSubscriptionId("sub-1");
			accessor.setUser(principal);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			WatchingSessionState session = new WatchingSessionState(
				UUID.randomUUID(),
				userId,
				contentId,
				"ws-1",
				"sub-1",
				Instant.now()
			);

			WatchingSessionDto dto = mock(WatchingSessionDto.class);

			when(watchingSessionService.join(
				userId,
				contentId,
				"ws-1",
				"sub-1"
			)).thenReturn(
				new WatchingSessionJoinResult(
					session,
					Optional.empty(),
					Set.of()
				)
			);

			when(watchingSessionService.toDto(session)).thenReturn(dto);
			when(watchingSessionService.count(contentId)).thenReturn(3L);

			listener.handleSubscribe(
				new SessionSubscribeEvent(this, message)
			);

			verify(watchingSessionService).join(
				userId,
				contentId,
				"ws-1",
				"sub-1"
			);

			verify(messagingTemplate).convertAndSend(
				eq("/sub/contents/" + contentId + "/watch"),
				argThat((WatchingSessionChange change) ->
					change.type() == ChangeType.JOIN
						&& change.watchingSession() == dto
						&& change.watcherCount() == 3L
				)
			);
		}

		@Test
		@DisplayName("시청 destination이 아니면 시청 세션에 참여하지 않는다")
		void ignoresOtherDestination() {
			UUID contentId = UUID.randomUUID();

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.SUBSCRIBE);

			accessor.setDestination(
				"/sub/contents/" + contentId + "/chat"
			);
			accessor.setSessionId("ws-1");
			accessor.setSubscriptionId("sub-1");
			accessor.setUser(principal);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			listener.handleSubscribe(
				new SessionSubscribeEvent(this, message)
			);

			verify(watchingSessionService, never()).join(
				any(),
				any(),
				anyString(),
				anyString()
			);
		}

		@Test
		@DisplayName("기존 시청 세션이 있으면 기존 콘텐츠에 LEAVE 후 새 콘텐츠에 JOIN을 전송한다")
		void switchesWatchingContent() {
			UUID userId = UUID.randomUUID();
			UUID previousContentId = UUID.randomUUID();
			UUID newContentId = UUID.randomUUID();

			when(principal.getName()).thenReturn(userId.toString());

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.SUBSCRIBE);

			accessor.setDestination(
				"/sub/contents/" + newContentId + "/watch"
			);
			accessor.setSessionId("new-ws");
			accessor.setSubscriptionId("new-sub");
			accessor.setUser(principal);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			WatchingSessionState previousSession = new WatchingSessionState(
				UUID.randomUUID(),
				userId,
				previousContentId,
				"old-ws",
				"old-sub",
				Instant.now()
			);

			WatchingSessionState newSession = new WatchingSessionState(
				UUID.randomUUID(),
				userId,
				newContentId,
				"new-ws",
				"new-sub",
				Instant.now()
			);

			WatchingSessionDto previousDto = mock(WatchingSessionDto.class);
			WatchingSessionDto newDto = mock(WatchingSessionDto.class);

			when(watchingSessionService.join(
				userId,
				newContentId,
				"new-ws",
				"new-sub"
			)).thenReturn(
				new WatchingSessionJoinResult(
					newSession,
					Optional.of(previousSession),
					Set.of("old-sub")
				)
			);

			when(watchingSessionService.toDto(previousSession))
				.thenReturn(previousDto);

			when(watchingSessionService.toDto(newSession))
				.thenReturn(newDto);

			when(watchingSessionService.count(previousContentId))
				.thenReturn(2L);

			when(watchingSessionService.count(newContentId))
				.thenReturn(4L);

			listener.handleSubscribe(
				new SessionSubscribeEvent(this, message)
			);

			verify(watchingSubscriptionBroker).unsubscribeAll(
				"old-ws",
				Set.of("old-sub")
			);

			verify(messagingTemplate).convertAndSend(
				"/sub/contents/" + previousContentId + "/watch",
				new WatchingSessionChange(
					ChangeType.LEAVE,
					previousDto,
					2L
				)
			);

			verify(messagingTemplate).convertAndSend(
				"/sub/contents/" + newContentId + "/watch",
				new WatchingSessionChange(
					ChangeType.JOIN,
					newDto,
					4L
				)
			);
		}

		@Test
		@DisplayName("같은 콘텐츠를 다시 구독하면 LEAVE 없이 새 세션의 JOIN만 전송한다")
		void resubscribesSameContent() {
			UUID userId = UUID.randomUUID();
			UUID contentId = UUID.randomUUID();

			when(principal.getName()).thenReturn(userId.toString());

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.SUBSCRIBE);

			accessor.setDestination(
				"/sub/contents/" + contentId + "/watch"
			);
			accessor.setSessionId("new-ws");
			accessor.setSubscriptionId("new-sub");
			accessor.setUser(principal);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			WatchingSessionState previousSession = new WatchingSessionState(
				UUID.randomUUID(),
				userId,
				contentId,
				"old-ws",
				"old-sub",
				Instant.now()
			);

			WatchingSessionState newSession = new WatchingSessionState(
				UUID.randomUUID(),
				userId,
				contentId,
				"new-ws",
				"new-sub",
				Instant.now()
			);

			WatchingSessionDto newDto = mock(WatchingSessionDto.class);

			when(watchingSessionService.join(
				userId,
				contentId,
				"new-ws",
				"new-sub"
			)).thenReturn(
				new WatchingSessionJoinResult(
					newSession,
					Optional.of(previousSession),
					Set.of()
				)
			);

			when(watchingSessionService.toDto(newSession))
				.thenReturn(newDto);

			when(watchingSessionService.count(contentId))
				.thenReturn(1L);

			listener.handleSubscribe(
				new SessionSubscribeEvent(this, message)
			);

			verify(watchingSubscriptionBroker, never())
				.unsubscribeAll(anyString(), anySet());

			verify(messagingTemplate).convertAndSend(
				"/sub/contents/" + contentId + "/watch",
				new WatchingSessionChange(
					ChangeType.JOIN,
					newDto,
					1L
				)
			);

			verify(watchingSessionService, never()).toDto(previousSession);

			verify(messagingTemplate, times(1))
				.convertAndSend(
					eq("/sub/contents/" + contentId + "/watch"),
					any(WatchingSessionChange.class)
				);
		}
	}

	@Nested
	@DisplayName("시청 구독 해제")
	class Unsubscribe {

		@Test
		@DisplayName("구독을 해제하면 해당 구독의 시청 세션을 해제한다")
		void success() {
			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);

			accessor.setSessionId("ws-1");
			accessor.setSubscriptionId("sub-1");

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			UUID contentId = UUID.randomUUID();

			WatchingSessionState session = new WatchingSessionState(
				UUID.randomUUID(),
				UUID.randomUUID(),
				contentId,
				"ws-1",
				"sub-1",
				Instant.now()
			);

			WatchingSessionDto dto = mock(WatchingSessionDto.class);

			when(watchingSessionService.leaveSubscription(
				"ws-1",
				"sub-1"
			)).thenReturn(Optional.of(session));

			when(watchingSessionService.toDto(session)).thenReturn(dto);
			when(watchingSessionService.count(contentId)).thenReturn(2L);

			listener.handleUnsubscribe(
				new SessionUnsubscribeEvent(this, message)
			);

			verify(watchingSessionService).leaveSubscription(
				"ws-1",
				"sub-1"
			);

			verify(messagingTemplate).convertAndSend(
				eq("/sub/contents/" + contentId + "/watch"),
				argThat((WatchingSessionChange change) ->
					change.type() == ChangeType.LEAVE
						&& change.watchingSession() == dto
						&& change.watcherCount() == 2L
				)
			);
		}
	}

	@Nested
	@DisplayName("웹소켓 연결 종료")
	class Disconnect {

		@Test
		@DisplayName("웹소켓 연결이 종료되면 시청 세션을 해제한다")
		void success() {
			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.DISCONNECT);

			accessor.setSessionId("ws-1");

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			UUID contentId = UUID.randomUUID();

			WatchingSessionState session = new WatchingSessionState(
				UUID.randomUUID(),
				UUID.randomUUID(),
				contentId,
				"ws-1",
				"sub-1",
				Instant.now()
			);

			WatchingSessionDto dto = mock(WatchingSessionDto.class);

			when(watchingSessionService.leave("ws-1"))
				.thenReturn(Optional.of(session));

			when(watchingSessionService.toDto(session)).thenReturn(dto);
			when(watchingSessionService.count(contentId)).thenReturn(1L);

			listener.handleDisconnect(
				new SessionDisconnectEvent(
					this,
					message,
					"ws-1",
					CloseStatus.NORMAL
				)
			);

			verify(watchingSessionService).leave("ws-1");

			verify(messagingTemplate).convertAndSend(
				eq("/sub/contents/" + contentId + "/watch"),
				argThat((WatchingSessionChange change) ->
					change.type() == ChangeType.LEAVE
						&& change.watchingSession() == dto
						&& change.watcherCount() == 1L
				)
			);
		}
	}
}