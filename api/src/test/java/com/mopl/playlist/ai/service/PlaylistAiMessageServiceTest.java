package com.mopl.playlist.ai.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.enums.PlaylistAiMessageRole;
import com.mopl.core.domain.playlist.entity.PlaylistAiMessage;
import com.mopl.playlist.ai.dto.AiPlaylistMessageResponse;
import com.mopl.playlist.ai.repository.PlaylistAiMessageRepository;
import com.mopl.common.exception.MoplException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PlaylistAiMessageServiceTest {

	@Mock
	private PlaylistAiMessageRepository messageRepository;

	private PlaylistAiMessageService messageService;

	@BeforeEach
	void setUp() {
		messageService =
			new PlaylistAiMessageService(messageRepository);
	}

	@Test
	@DisplayName("다음 메시지가 있으면 커서 정보와 hasNext를 반환한다")
	void getMessages_hasNext() {
		UUID sessionId = UUID.randomUUID();

		PlaylistAiMessage first = createMessage(
			UUID.randomUUID(),
			PlaylistAiMessageRole.USER,
			"첫 번째 메시지",
			LocalDateTime.of(2026, 9, 29, 12, 0)
		);

		PlaylistAiMessage second = createMessage(
			UUID.randomUUID(),
			PlaylistAiMessageRole.ASSISTANT,
			"두 번째 메시지",
			LocalDateTime.of(2026, 9, 29, 11, 0)
		);

		PlaylistAiMessage lookAhead =
			mock(PlaylistAiMessage.class);

		when(messageRepository.findBySessionCursor(
			sessionId,
			null,
			null,
			3,
			false
		)).thenReturn(
			List.of(first, second, lookAhead)
		);

		when(messageRepository.countBySession(sessionId))
			.thenReturn(3L);

		CursorResponse<AiPlaylistMessageResponse> response =
			messageService.getMessages(
				sessionId,
				null,
				null,
				2,
				"DESCENDING"
			);

		assertThat(response.data())
			.hasSize(2);

		assertThat(response.data())
			.extracting(AiPlaylistMessageResponse::content)
			.containsExactly(
				"첫 번째 메시지",
				"두 번째 메시지"
			);

		assertThat(response.hasNext())
			.isTrue();

		assertThat(response.nextCursor())
			.isEqualTo("2026-09-29T11:00:00");

		assertThat(response.nextIdAfter())
			.isEqualTo(second.getId().toString());

		assertThat(response.totalCount())
			.isEqualTo(3L);

		assertThat(response.sortBy())
			.isEqualTo("CREATED_AT");

		assertThat(response.sortDirection())
			.isEqualTo("DESCENDING");

		verify(messageRepository).findBySessionCursor(
			sessionId,
			null,
			null,
			3,
			false
		);

		verify(messageRepository)
			.countBySession(sessionId);
	}

	@Test
	@DisplayName("cursor와 idAfter 중 하나만 전달되면 INVALID_INPUT_VALUE 예외가 발생한다")
	void getMessages_cursorAndIdAfterMustBeProvidedTogether() {
		UUID sessionId = UUID.randomUUID();

		assertThatThrownBy(() ->
			messageService.getMessages(
				sessionId,
				"2026-09-29T12:00:00",
				null,
				20,
				"DESCENDING"
			)
		)
			.isInstanceOfSatisfying(MoplException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
			);

		verifyNoInteractions(messageRepository);
	}

	@Test
	@DisplayName("limit가 0 이하이면 INVALID_INPUT_VALUE 예외가 발생한다")
	void getMessages_invalidLimit() {
		UUID sessionId = UUID.randomUUID();

		assertThatThrownBy(() ->
			messageService.getMessages(
				sessionId,
				null,
				null,
				0,
				"DESCENDING"
			)
		)
			.isInstanceOfSatisfying(MoplException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
			);

		verifyNoInteractions(messageRepository);
	}

	@Test
	@DisplayName("지원하지 않는 정렬 방향이면 INVALID_INPUT_VALUE 예외가 발생한다")
	void getMessages_invalidSortDirection() {
		UUID sessionId = UUID.randomUUID();

		assertThatThrownBy(() ->
			messageService.getMessages(
				sessionId,
				null,
				null,
				20,
				"INVALID"
			)
		)
			.isInstanceOfSatisfying(MoplException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
			);

		verifyNoInteractions(messageRepository);
	}

	@Test
	@DisplayName("limit가 최대값을 초과하면 100개 기준으로 조회한다")
	void getMessages_capsLimitAtMax() {
		UUID sessionId = UUID.randomUUID();

		when(messageRepository.findBySessionCursor(
			sessionId,
			null,
			null,
			101,
			false
		)).thenReturn(List.of());

		when(messageRepository.countBySession(sessionId))
			.thenReturn(0L);

		CursorResponse<AiPlaylistMessageResponse> response =
			messageService.getMessages(
				sessionId,
				null,
				null,
				200,
				"DESCENDING"
			);

		assertThat(response.data()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
		assertThat(response.nextIdAfter()).isNull();

		verify(messageRepository).findBySessionCursor(
			sessionId,
			null,
			null,
			101,
			false
		);
	}

	@Test
	@DisplayName("idAfter 형식이 잘못되면 INVALID_INPUT_VALUE 예외가 발생한다")
	void getMessages_invalidIdAfter() {
		UUID sessionId = UUID.randomUUID();

		assertThatThrownBy(() ->
			messageService.getMessages(
				sessionId,
				"2026-09-29T12:00:00",
				"invalid-id",
				20,
				"DESCENDING"
			)
		)
			.isInstanceOfSatisfying(MoplException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
			);

		verifyNoInteractions(messageRepository);
	}

	private PlaylistAiMessage createMessage(
		UUID id,
		PlaylistAiMessageRole role,
		String content,
		LocalDateTime createdAt
	) {
		PlaylistAiMessage message =
			mock(PlaylistAiMessage.class);

		when(message.getId())
			.thenReturn(id);
		when(message.getRole())
			.thenReturn(role);
		when(message.getContent())
			.thenReturn(content);
		when(message.getCreatedAt())
			.thenReturn(createdAt);

		return message;
	}
}