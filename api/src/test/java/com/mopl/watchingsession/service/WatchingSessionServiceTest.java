package com.mopl.watchingsession.service;

import com.mopl.common.exception.MoplException;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.user.entity.User;
import com.mopl.core.domain.watchingsession.model.WatchingSessionState;
import com.mopl.infrastructure.content.repository.ContentSummaryQueryResult;
import com.mopl.infrastructure.content.repository.ContentSummaryRepository;
import com.mopl.infrastructure.watchingsession.repository.WatchingSessionRedisRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.WatchingSessionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.mockito.Mockito.*;

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

	private WatchingSessionService watchingSessionService;

	@BeforeEach
	void setUp() {
		watchingSessionService = new WatchingSessionService(
			watchingSessionRepository,
			userRepository,
			contentRepository,
			contentSummaryRepository
		);
	}

	@Test
	@DisplayName("사용자의 현재 시청 세션이 없으면 null을 반환한다")
	void getWatchingSession_returnsNull_whenSessionDoesNotExist() {
		UUID watcherId = UUID.randomUUID();

		when(watchingSessionRepository.findByUserId(watcherId))
			.thenReturn(Optional.empty());

		WatchingSessionResponse result =
			watchingSessionService.getWatchingSession(watcherId);

		assertThat(result).isNull();

		verify(watchingSessionRepository).findByUserId(watcherId);
		verifyNoInteractions(
			userRepository,
			contentRepository,
			contentSummaryRepository
		);
	}

	@Test
	@DisplayName("사용자의 현재 시청 세션을 조회한다")
	void getWatchingSession_returnsWatchingSession() {
		UUID sessionId = UUID.randomUUID();
		UUID watcherId = UUID.randomUUID();
		UUID contentId = UUID.randomUUID();
		Instant createdAt = Instant.parse("2026-09-26T12:00:00Z");

		WatchingSessionState session = new WatchingSessionState(
			sessionId,
			watcherId,
			contentId,
			"ws-session",
			"subscription-1",
			createdAt
		);

		User watcher = mock(User.class);
		Content content = mock(Content.class);

		when(watchingSessionRepository.findByUserId(watcherId))
			.thenReturn(Optional.of(session));

		when(userRepository.findByIdAndDeletedAtIsNull(watcherId))
			.thenReturn(Optional.of(watcher));

		when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
			.thenReturn(Optional.of(content));

		when(watcher.getId()).thenReturn(watcherId);
		when(watcher.getName()).thenReturn("예성");
		when(watcher.getProfileImageUrl()).thenReturn("profile.jpg");

		when(content.getId()).thenReturn(contentId);
		when(content.getTitle()).thenReturn("테스트 콘텐츠");
		when(contentSummaryRepository.findByContentId(contentId))
			.thenReturn(new ContentSummaryQueryResult(
				List.of(),
				4.5,
				10
			));

		WatchingSessionResponse result =
			watchingSessionService.getWatchingSession(watcherId);

		assertThat(result.id()).isEqualTo(sessionId);
		assertThat(result.createdAt()).isEqualTo(createdAt);

		assertThat(result.watcher().userId()).isEqualTo(watcherId);
		assertThat(result.watcher().name()).isEqualTo("예성");
		assertThat(result.watcher().profileImageUrl()).isEqualTo("profile.jpg");

		assertThat(result.content().id()).isEqualTo(contentId);
		assertThat(result.content().title()).isEqualTo("테스트 콘텐츠");
		assertThat(result.content().averageRating()).isEqualTo(4.5);
		assertThat(result.content().reviewCount()).isEqualTo(10);
	}

	@Test
	@DisplayName("콘텐츠의 시청 세션 목록을 커서 페이지네이션으로 조회한다")
	void getWatchingSessions_returnsCursorPage() {
		UUID contentId = UUID.randomUUID();

		UUID watcherId1 = UUID.randomUUID();
		UUID watcherId2 = UUID.randomUUID();
		UUID watcherId3 = UUID.randomUUID();

		WatchingSessionState session1 = new WatchingSessionState(
			UUID.randomUUID(),
			watcherId1,
			contentId,
			"ws-1",
			"sub-1",
			Instant.parse("2026-09-26T10:00:00Z")
		);

		WatchingSessionState session2 = new WatchingSessionState(
			UUID.randomUUID(),
			watcherId2,
			contentId,
			"ws-2",
			"sub-2",
			Instant.parse("2026-09-26T11:00:00Z")
		);

		WatchingSessionState session3 = new WatchingSessionState(
			UUID.randomUUID(),
			watcherId3,
			contentId,
			"ws-3",
			"sub-3",
			Instant.parse("2026-09-26T12:00:00Z")
		);

		Content content = mock(Content.class);
		User watcher1 = mock(User.class);
		User watcher2 = mock(User.class);
		User watcher3 = mock(User.class);

		when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
			.thenReturn(Optional.of(content));

		when(content.getId()).thenReturn(contentId);

		when(contentSummaryRepository.findByContentId(contentId))
			.thenReturn(new ContentSummaryQueryResult(
				List.of(),
				4.5,
				10
			));

		when(watchingSessionRepository.findAllByContentId(contentId))
			.thenReturn(List.of(session3, session1, session2));

		when(userRepository.findByIdAndDeletedAtIsNull(watcherId1))
			.thenReturn(Optional.of(watcher1));
		when(userRepository.findByIdAndDeletedAtIsNull(watcherId2))
			.thenReturn(Optional.of(watcher2));
		when(userRepository.findByIdAndDeletedAtIsNull(watcherId3))
			.thenReturn(Optional.of(watcher3));

		when(watcher1.getId()).thenReturn(watcherId1);
		when(watcher1.getName()).thenReturn("사용자1");

		when(watcher2.getId()).thenReturn(watcherId2);
		when(watcher2.getName()).thenReturn("사용자2");

		when(watcher3.getId()).thenReturn(watcherId3);
		when(watcher3.getName()).thenReturn("사용자3");

		CursorResponse<WatchingSessionResponse> result =
			watchingSessionService.getWatchingSessions(
				contentId,
				null,
				null,
				null,
				2,
				"createdAt",
				"ASCENDING"
			);

		assertThat(result.data()).hasSize(2);
		assertThat(result.data().get(0).id()).isEqualTo(session1.id());
		assertThat(result.data().get(1).id()).isEqualTo(session2.id());

		assertThat(result.hasNext()).isTrue();
		assertThat(result.totalCount()).isEqualTo(3);
		assertThat(result.nextCursor())
			.isEqualTo(session2.createdAt().toString());
		assertThat(result.nextIdAfter())
			.isEqualTo(session2.id().toString());

		assertThat(result.sortBy()).isEqualTo("createdAt");
		assertThat(result.sortDirection()).isEqualTo("ASCENDING");
	}

	@Test
	@DisplayName("커서 이후의 시청 세션 목록을 조회한다")
	void getWatchingSessions_returnsSessionsAfterCursor() {
		UUID contentId = UUID.randomUUID();

		UUID watcherId1 = UUID.randomUUID();
		UUID watcherId2 = UUID.randomUUID();
		UUID watcherId3 = UUID.randomUUID();

		WatchingSessionState session1 = new WatchingSessionState(
			UUID.randomUUID(),
			watcherId1,
			contentId,
			"ws-1",
			"sub-1",
			Instant.parse("2026-09-26T10:00:00Z")
		);

		WatchingSessionState session2 = new WatchingSessionState(
			UUID.randomUUID(),
			watcherId2,
			contentId,
			"ws-2",
			"sub-2",
			Instant.parse("2026-09-26T11:00:00Z")
		);

		WatchingSessionState session3 = new WatchingSessionState(
			UUID.randomUUID(),
			watcherId3,
			contentId,
			"ws-3",
			"sub-3",
			Instant.parse("2026-09-26T12:00:00Z")
		);

		Content content = mock(Content.class);

		User watcher1 = mock(User.class);
		User watcher2 = mock(User.class);
		User watcher3 = mock(User.class);

		when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
			.thenReturn(Optional.of(content));
		when(content.getId()).thenReturn(contentId);

		when(contentSummaryRepository.findByContentId(contentId))
			.thenReturn(new ContentSummaryQueryResult(
				List.of(),
				4.5,
				10
			));

		when(watchingSessionRepository.findAllByContentId(contentId))
			.thenReturn(List.of(session1, session2, session3));

		when(userRepository.findByIdAndDeletedAtIsNull(watcherId1))
			.thenReturn(Optional.of(watcher1));
		when(userRepository.findByIdAndDeletedAtIsNull(watcherId2))
			.thenReturn(Optional.of(watcher2));
		when(userRepository.findByIdAndDeletedAtIsNull(watcherId3))
			.thenReturn(Optional.of(watcher3));

		when(watcher1.getId()).thenReturn(watcherId1);
		when(watcher1.getName()).thenReturn("사용자1");

		when(watcher2.getId()).thenReturn(watcherId2);
		when(watcher2.getName()).thenReturn("사용자2");

		when(watcher3.getId()).thenReturn(watcherId3);
		when(watcher3.getName()).thenReturn("사용자3");

		CursorResponse<WatchingSessionResponse> result =
			watchingSessionService.getWatchingSessions(
				contentId,
				null,
				session2.createdAt().toString(),
				session2.id(),
				2,
				"createdAt",
				"ASCENDING"
			);

		assertThat(result.data()).hasSize(1);
		assertThat(result.data().getFirst().id()).isEqualTo(session3.id());

		assertThat(result.hasNext()).isFalse();
		assertThat(result.nextCursor()).isNull();
		assertThat(result.nextIdAfter()).isNull();

		// 커서와 무관하게 전체 검색 결과 수
		assertThat(result.totalCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("시청자 이름으로 시청 세션을 검색한다")
	void getWatchingSessions_filtersByWatcherName() {
		UUID contentId = UUID.randomUUID();
		UUID watcherId1 = UUID.randomUUID();
		UUID watcherId2 = UUID.randomUUID();

		WatchingSessionState session1 = new WatchingSessionState(
			UUID.randomUUID(),
			watcherId1,
			contentId,
			"ws-1",
			"sub-1",
			Instant.parse("2026-09-26T10:00:00Z")
		);

		WatchingSessionState session2 = new WatchingSessionState(
			UUID.randomUUID(),
			watcherId2,
			contentId,
			"ws-2",
			"sub-2",
			Instant.parse("2026-09-26T11:00:00Z")
		);

		Content content = mock(Content.class);
		User watcher1 = mock(User.class);
		User watcher2 = mock(User.class);

		when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
			.thenReturn(Optional.of(content));
		when(content.getId()).thenReturn(contentId);

		when(contentSummaryRepository.findByContentId(contentId))
			.thenReturn(new ContentSummaryQueryResult(
				List.of(),
				4.5,
				10
			));

		when(watchingSessionRepository.findAllByContentId(contentId))
			.thenReturn(List.of(session1, session2));

		when(userRepository.findByIdAndDeletedAtIsNull(watcherId1))
			.thenReturn(Optional.of(watcher1));
		when(userRepository.findByIdAndDeletedAtIsNull(watcherId2))
			.thenReturn(Optional.of(watcher2));

		when(watcher1.getId()).thenReturn(watcherId1);
		when(watcher1.getName()).thenReturn("예성");

		when(watcher2.getId()).thenReturn(watcherId2);
		when(watcher2.getName()).thenReturn("성규");

		CursorResponse<WatchingSessionResponse> result =
			watchingSessionService.getWatchingSessions(
				contentId,
				"예성",
				null,
				null,
				20,
				"createdAt",
				"ASCENDING"
			);

		assertThat(result.data()).hasSize(1);
		assertThat(result.data().getFirst().watcher().userId())
			.isEqualTo(watcherId1);

		assertThat(result.data().getFirst().watcher().name())
			.isEqualTo("예성");

		assertThat(result.totalCount()).isEqualTo(1);
		assertThat(result.hasNext()).isFalse();
	}

	@Test
	@DisplayName("limit이 최대값을 초과하면 100개로 제한한다")
	void getWatchingSessions_capsLimitAtMaxLimit() {
		UUID contentId = UUID.randomUUID();

		Content content = mock(Content.class);

		when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
			.thenReturn(Optional.of(content));
		when(content.getId()).thenReturn(contentId);

		when(contentSummaryRepository.findByContentId(contentId))
			.thenReturn(new ContentSummaryQueryResult(
				List.of(),
				4.5,
				10
			));

		List<WatchingSessionState> sessions = new java.util.ArrayList<>();

		for (int i = 0; i < 101; i++) {
			UUID watcherId = UUID.randomUUID();

			WatchingSessionState session = new WatchingSessionState(
				UUID.randomUUID(),
				watcherId,
				contentId,
				"ws-" + i,
				"sub-" + i,
				Instant.parse("2026-09-26T10:00:00Z").plusSeconds(i)
			);

			User watcher = mock(User.class);

			when(userRepository.findByIdAndDeletedAtIsNull(watcherId))
				.thenReturn(Optional.of(watcher));
			when(watcher.getId()).thenReturn(watcherId);
			when(watcher.getName()).thenReturn("사용자" + i);

			sessions.add(session);
		}

		when(watchingSessionRepository.findAllByContentId(contentId))
			.thenReturn(sessions);

		CursorResponse<WatchingSessionResponse> result =
			watchingSessionService.getWatchingSessions(
				contentId,
				null,
				null,
				null,
				101,
				"createdAt",
				"ASCENDING"
			);

		assertThat(result.data()).hasSize(100);
		assertThat(result.hasNext()).isTrue();
		assertThat(result.totalCount()).isEqualTo(101);
	}

	@Test
	@DisplayName("limit이 0 이하이면 예외가 발생한다")
	void getWatchingSessions_throwsException_whenLimitIsInvalid() {
		assertThatThrownBy(() ->
			watchingSessionService.getWatchingSessions(
				UUID.randomUUID(),
				null,
				null,
				null,
				0,
				"createdAt",
				"ASCENDING"
			)
		).isInstanceOf(MoplException.class);
	}

	@Test
	@DisplayName("cursor와 idAfter 중 하나만 전달하면 예외가 발생한다")
	void getWatchingSessions_throwsException_whenCursorPairIsIncomplete() {
		assertThatThrownBy(() ->
			watchingSessionService.getWatchingSessions(
				UUID.randomUUID(),
				null,
				"2026-09-26T10:00:00Z",
				null,
				20,
				"createdAt",
				"ASCENDING"
			)
		).isInstanceOf(MoplException.class);
	}

	@Test
	@DisplayName("지원하지 않는 정렬 기준이면 예외가 발생한다")
	void getWatchingSessions_throwsException_whenSortByIsInvalid() {
		assertThatThrownBy(() ->
			watchingSessionService.getWatchingSessions(
				UUID.randomUUID(),
				null,
				null,
				null,
				20,
				"title",
				"ASCENDING"
			)
		).isInstanceOf(MoplException.class);
	}

	@Test
	@DisplayName("지원하지 않는 정렬 방향이면 예외가 발생한다")
	void getWatchingSessions_throwsException_whenSortDirectionIsInvalid() {
		assertThatThrownBy(() ->
			watchingSessionService.getWatchingSessions(
				UUID.randomUUID(),
				null,
				null,
				null,
				20,
				"createdAt",
				"INVALID"
			)
		).isInstanceOf(MoplException.class);
	}
}