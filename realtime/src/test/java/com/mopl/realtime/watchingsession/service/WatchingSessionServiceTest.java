package com.mopl.realtime.watchingsession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.event.FollowingWatchStartedEvent;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.user.entity.User;
import com.mopl.core.domain.watchingsession.model.WatchingSessionState;
import com.mopl.infrastructure.content.repository.ContentSummaryQueryResult;
import com.mopl.infrastructure.content.repository.ContentSummaryRepository;
import com.mopl.infrastructure.watchingsession.repository.WatchingSessionRedisRepository;
import com.mopl.realtime.contentchat.repository.ContentRepository;
import com.mopl.realtime.contentchat.repository.UserRepository;
import com.mopl.realtime.watchingsession.dto.WatchingSessionDto;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class WatchingSessionServiceTest {

	@Mock
	private WatchingSessionRedisRepository watchingSessionRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private ContentRepository contentRepository;

	@Mock
	private ContentSummaryRepository contentSummaryRepository;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private WatchingSessionService watchingSessionService;

	@BeforeEach
	void setUp() {
		watchingSessionService = new WatchingSessionService(
			watchingSessionRepository,
			userRepository,
			contentRepository,
			contentSummaryRepository,
			eventPublisher
		);
	}

	@Nested
	@DisplayName("시청 세션 참여")
	class Join {

		@Test
		@DisplayName("시청 세션을 생성하고 저장한다")
		void success() {
			UUID userId = UUID.randomUUID();
			UUID contentId = UUID.randomUUID();
			List<UUID> followerIds = List.of(UUID.randomUUID(), UUID.randomUUID());
			User watcher = mock(User.class);
			Content content = mock(Content.class);

			when(userRepository.findByIdAndDeletedAtIsNull(userId))
				.thenReturn(Optional.of(watcher));
			when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
				.thenReturn(Optional.of(content));
			when(userRepository.findFollowerIds(userId)).thenReturn(followerIds);
			when(watcher.getName()).thenReturn("시청자");
			when(content.getTitle()).thenReturn("콘텐츠 제목");

			when(watchingSessionRepository.findByUserId(userId))
				.thenReturn(Optional.empty());

			WatchingSessionJoinResult result = watchingSessionService.join(
				userId,
				contentId,
				"ws-1",
				"sub-1"
			);

			ArgumentCaptor<WatchingSessionState> captor =
				ArgumentCaptor.forClass(WatchingSessionState.class);

			verify(watchingSessionRepository).save(captor.capture());

			WatchingSessionState saved = captor.getValue();

			assertThat(saved.watcherId()).isEqualTo(userId);
			assertThat(saved.contentId()).isEqualTo(contentId);
			assertThat(saved.webSocketSessionId()).isEqualTo("ws-1");
			assertThat(saved.subscriptionId()).isEqualTo("sub-1");

			assertThat(result.joinedSession()).isEqualTo(saved);
			assertThat(result.leftSession()).isEmpty();
			verify(eventPublisher).publishEvent(
				new FollowingWatchStartedEvent(followerIds, "시청자", "콘텐츠 제목")
			);
		}

		@Test
		@DisplayName("기존 시청 세션이 있으면 삭제하고 새 세션을 저장한다")
		void replacesExistingSession() {
			UUID userId = UUID.randomUUID();
			UUID contentId = UUID.randomUUID();

			mockValidUserAndContent(userId, contentId);

			WatchingSessionState previous = new WatchingSessionState(
				UUID.randomUUID(),
				userId,
				UUID.randomUUID(),
				"old-ws",
				"old-sub",
				Instant.now()
			);

			when(watchingSessionRepository.findByUserId(userId))
				.thenReturn(Optional.of(previous));

			WatchingSessionJoinResult result = watchingSessionService.join(
				userId,
				contentId,
				"new-ws",
				"new-sub"
			);

			assertThat(result.leftSession()).contains(previous);

			verify(watchingSessionRepository).delete(previous);
			verify(watchingSessionRepository)
				.save(any(WatchingSessionState.class));
		}

		@Test
		@DisplayName("존재하지 않는 콘텐츠로 참여하면 기존 시청 세션을 변경하지 않는다")
		void invalidContent_doesNotChangeExistingSession() {
			UUID userId = UUID.randomUUID();
			UUID invalidContentId = UUID.randomUUID();

			when(userRepository.findByIdAndDeletedAtIsNull(userId))
				.thenReturn(Optional.of(mock(User.class)));
			when(contentRepository.findByIdAndDeletedAtIsNull(invalidContentId))
				.thenReturn(Optional.empty());

			assertThatThrownBy(() -> watchingSessionService.join(
				userId,
				invalidContentId,
				"new-ws",
				"new-sub"
			)).isInstanceOf(NoSuchElementException.class);

			verifyNoInteractions(watchingSessionRepository);
		}

		private void mockValidUserAndContent(UUID userId, UUID contentId) {
			when(userRepository.findByIdAndDeletedAtIsNull(userId))
				.thenReturn(Optional.of(mock(User.class)));

			when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
				.thenReturn(Optional.of(mock(Content.class)));
		}
	}

	@Nested
	@DisplayName("시청 구독 해제")
	class LeaveSubscription {

		@Test
		@DisplayName("마지막 구독을 해제하면 시청 세션을 삭제한다")
		void matchingSubscription() {
			WatchingSessionState session = session("sub-1");

			when(watchingSessionRepository.findByWebSocketSessionId("ws-1"))
				.thenReturn(Optional.of(session));

			when(watchingSessionRepository.removeSubscription(
				"ws-1",
				"sub-1"
			)).thenReturn(true);

			when(watchingSessionRepository.hasSubscriptions("ws-1"))
				.thenReturn(false);

			Optional<WatchingSessionState> result =
				watchingSessionService.leaveSubscription(
					"ws-1",
					"sub-1"
				);

			assertThat(result).contains(session);

			verify(watchingSessionRepository).removeSubscription(
				"ws-1",
				"sub-1"
			);
			verify(watchingSessionRepository).hasSubscriptions("ws-1");
			verify(watchingSessionRepository).delete(session);
		}

		@Test
		@DisplayName("다른 구독이 남아 있으면 시청 세션을 삭제하지 않는다")
		void remainingSubscription() {
			WatchingSessionState session = session("sub-2");

			when(watchingSessionRepository.findByWebSocketSessionId("ws-1"))
				.thenReturn(Optional.of(session));

			when(watchingSessionRepository.removeSubscription(
				"ws-1",
				"sub-2"
			)).thenReturn(true);

			when(watchingSessionRepository.hasSubscriptions("ws-1"))
				.thenReturn(true);

			Optional<WatchingSessionState> result =
				watchingSessionService.leaveSubscription(
					"ws-1",
					"sub-2"
				);

			assertThat(result).isEmpty();

			verify(watchingSessionRepository).removeSubscription(
				"ws-1",
				"sub-2"
			);
			verify(watchingSessionRepository).hasSubscriptions("ws-1");
			verify(watchingSessionRepository, never())
				.delete(any(WatchingSessionState.class));
		}

		@Test
		@DisplayName("등록되지 않은 구독 ID를 해제하면 시청 세션을 삭제하지 않는다")
		void differentSubscription() {
			WatchingSessionState session = session("watch-sub");

			when(watchingSessionRepository.findByWebSocketSessionId("ws-1"))
				.thenReturn(Optional.of(session));

			when(watchingSessionRepository.removeSubscription(
				"ws-1",
				"chat-sub"
			)).thenReturn(false);

			Optional<WatchingSessionState> result =
				watchingSessionService.leaveSubscription(
					"ws-1",
					"chat-sub"
				);

			assertThat(result).isEmpty();

			verify(watchingSessionRepository).removeSubscription(
				"ws-1",
				"chat-sub"
			);
			verify(watchingSessionRepository, never())
				.hasSubscriptions(anyString());
			verify(watchingSessionRepository, never())
				.delete(any(WatchingSessionState.class));
		}
	}

	@Nested
	@DisplayName("웹소켓 연결 종료")
	class Leave {

		@Test
		@DisplayName("연결이 종료된 웹소켓의 시청 세션을 삭제한다")
		void success() {
			WatchingSessionState session = session("sub-1");

			when(watchingSessionRepository.findByWebSocketSessionId("ws-1"))
				.thenReturn(Optional.of(session));

			Optional<WatchingSessionState> result =
				watchingSessionService.leave("ws-1");

			assertThat(result).contains(session);
			verify(watchingSessionRepository).delete(session);
		}
	}

	private WatchingSessionState session(String subscriptionId) {
		return new WatchingSessionState(
			UUID.randomUUID(),
			UUID.randomUUID(),
			UUID.randomUUID(),
			"ws-1",
			subscriptionId,
			Instant.now()
		);
	}

	@Test
	@DisplayName("시청 세션 상태를 사용자와 콘텐츠 정보를 포함한 DTO로 변환한다")
	void toDtoSuccess() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID contentId = UUID.randomUUID();
		Instant createdAt = Instant.now();

		WatchingSessionState session = new WatchingSessionState(
			sessionId,
			userId,
			contentId,
			"ws-1",
			"sub-1",
			createdAt
		);

		User user = mock(User.class);
		Content content = mock(Content.class);

		when(user.getId()).thenReturn(userId);
		when(user.getName()).thenReturn("예성");
		when(user.getProfileImageUrl()).thenReturn("profile.jpg");

		when(content.getId()).thenReturn(contentId);
		when(content.getType()).thenReturn(ContentType.MOVIE);
		when(content.getTitle()).thenReturn("테스트 콘텐츠");
		when(content.getDescription()).thenReturn("테스트 설명");
		when(content.getThumbnailUrl()).thenReturn("thumbnail.jpg");

		ContentSummaryQueryResult summary = new ContentSummaryQueryResult(
			List.of("genre=action", "mood=exciting"),
			4.5,
			10
		);

		when(userRepository.findByIdAndDeletedAtIsNull(userId))
			.thenReturn(Optional.of(user));

		when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
			.thenReturn(Optional.of(content));

		when(contentSummaryRepository.findByContentId(contentId))
			.thenReturn(summary);

		WatchingSessionDto result = watchingSessionService.toDto(session);

		assertEquals(sessionId, result.id());
		assertEquals(createdAt, result.createdAt());

		assertEquals(userId, result.watcher().userId());
		assertEquals("예성", result.watcher().name());
		assertEquals("profile.jpg", result.watcher().profileImageUrl());

		assertEquals(contentId, result.content().id());
		assertEquals(ContentType.MOVIE, result.content().type());
		assertEquals("테스트 콘텐츠", result.content().title());
		assertEquals("테스트 설명", result.content().description());
		assertEquals("thumbnail.jpg", result.content().thumbnailUrl());

		assertEquals(
			List.of("genre=action", "mood=exciting"),
			result.content().tags()
		);
		assertEquals(4.5, result.content().averageRating());
		assertEquals(10, result.content().reviewCount());
	}
}
