package com.mopl.playlist.ai.service;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.playlist.PlaylistAiSessionNotFoundException;
import com.mopl.playlist.ai.repository.PlaylistAiSessionRepository;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.domain.playlist.entity.PlaylistAiSession;
import com.mopl.playlist.ai.dto.AiPlaylistSessionResponse;

import java.time.LocalDateTime;
import java.util.List;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaylistAiSessionServiceTest {

	@Test
	@DisplayName("AI 플레이리스트 대화 세션을 찾을 수 없으면 예외가 발생한다")
	void getSessionFailsWhenSessionNotFound() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();

		PlaylistAiSessionRepository sessionRepository =
			mock(PlaylistAiSessionRepository.class);
		UserRepository userRepository =
			mock(UserRepository.class);

		when(sessionRepository.findByIdAndUser_Id(sessionId, userId))
			.thenReturn(Optional.empty());

		PlaylistAiSessionService service =
			new PlaylistAiSessionService(
				sessionRepository,
				userRepository
			);

		assertThrows(
			PlaylistAiSessionNotFoundException.class,
			() -> service.getSession(sessionId, userId)
		);
	}

	@Test
	@DisplayName("AI 플레이리스트 대화 세션 목록을 커서 페이지네이션으로 조회한다")
	void getSessions() {
		UUID userId = UUID.randomUUID();
		UUID firstSessionId = UUID.randomUUID();
		UUID secondSessionId = UUID.randomUUID();

		PlaylistAiSession firstSession =
			mock(PlaylistAiSession.class);
		PlaylistAiSession secondSession =
			mock(PlaylistAiSession.class);

		LocalDateTime firstUpdatedAt =
			LocalDateTime.of(2026, 9, 29, 12, 0);
		LocalDateTime secondUpdatedAt =
			LocalDateTime.of(2026, 9, 29, 11, 0);

		when(firstSession.getId()).thenReturn(firstSessionId);
		when(firstSession.getTitle()).thenReturn("첫 번째 대화");
		when(firstSession.getCreatedAt()).thenReturn(firstUpdatedAt);
		when(firstSession.getUpdatedAt()).thenReturn(firstUpdatedAt);

		when(secondSession.getId()).thenReturn(secondSessionId);
		when(secondSession.getTitle()).thenReturn("두 번째 대화");
		when(secondSession.getCreatedAt()).thenReturn(secondUpdatedAt);
		when(secondSession.getUpdatedAt()).thenReturn(secondUpdatedAt);

		PlaylistAiSessionRepository sessionRepository =
			mock(PlaylistAiSessionRepository.class);
		UserRepository userRepository =
			mock(UserRepository.class);

		when(sessionRepository.findByUserCursor(
			userId,
			null,
			null,
			2,
			false
		)).thenReturn(List.of(firstSession, secondSession));

		when(sessionRepository.countByUser(userId))
			.thenReturn(2L);

		PlaylistAiSessionService service =
			new PlaylistAiSessionService(
				sessionRepository,
				userRepository
			);

		CursorResponse<AiPlaylistSessionResponse> response =
			service.getSessions(
				userId,
				null,
				null,
				1,
				"DESCENDING"
			);

		assertEquals(1, response.data().size());
		assertEquals(firstSessionId, response.data().getFirst().id());
		assertEquals(
			"2026-09-29T12:00:00",
			response.nextCursor()
		);
		assertEquals(
			firstSessionId.toString(),
			response.nextIdAfter()
		);
		assertTrue(response.hasNext());
		assertEquals(2L, response.totalCount());
	}

	@Test
	@DisplayName("커서와 idAfter 중 하나만 전달하면 예외가 발생한다")
	void getSessionsFailsWhenCursorPairIsIncomplete() {
		UUID userId = UUID.randomUUID();

		PlaylistAiSessionRepository sessionRepository =
			mock(PlaylistAiSessionRepository.class);
		UserRepository userRepository =
			mock(UserRepository.class);

		PlaylistAiSessionService service =
			new PlaylistAiSessionService(
				sessionRepository,
				userRepository
			);

		assertThrows(
			MoplException.class,
			() -> service.getSessions(
				userId,
				"2026-09-29T12:00:00",
				null,
				20,
				"DESCENDING"
			)
		);
	}
}