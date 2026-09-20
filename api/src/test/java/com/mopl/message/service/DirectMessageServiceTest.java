package com.mopl.message.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.message.ConversationAccessDeniedException;
import com.mopl.common.exception.message.ConversationNotFoundException;
import com.mopl.common.exception.message.DirectMessageNotFoundException;
import com.mopl.common.exception.message.DirectMessageReadNotAllowedException;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.message.dto.CursorResponse;
import com.mopl.message.dto.DirectMessageResponse;
import com.mopl.message.dto.DirectMessageSortBy;
import com.mopl.message.dto.SortDirection;
import com.mopl.message.repository.ConversationRepository;
import com.mopl.message.repository.DirectMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DirectMessageServiceTest {

	@Mock
	private DirectMessageRepository directMessageRepository;

	@Mock
	private ConversationRepository conversationRepository;

	private DirectMessageService directMessageService;

	private UUID requesterId;
	private UUID opponentId;
	private User requester;
	private User opponent;
	private UUID conversationId;
	private Conversation conversation;

	@BeforeEach
	void setUp() {
		directMessageService = new DirectMessageService(directMessageRepository, conversationRepository);

		requesterId = UUID.randomUUID();
		opponentId = UUID.randomUUID();

		requester = mock(User.class);
		lenient().when(requester.getId()).thenReturn(requesterId);
		lenient().when(requester.getName()).thenReturn("요청자");
		lenient().when(requester.getProfileImageUrl()).thenReturn("http://image.url/requester");

		opponent = mock(User.class);
		lenient().when(opponent.getId()).thenReturn(opponentId);
		lenient().when(opponent.getName()).thenReturn("상대방");
		lenient().when(opponent.getProfileImageUrl()).thenReturn("http://image.url/opponent");

		conversationId = UUID.randomUUID();
		conversation = mock(Conversation.class);
		lenient().when(conversation.getId()).thenReturn(conversationId);
		lenient().when(conversation.getUser1()).thenReturn(requester);
		lenient().when(conversation.getUser2()).thenReturn(opponent);
	}

	private DirectMessage mockDirectMessage(UUID id, User sender, User receiver) {
		DirectMessage dm = mock(DirectMessage.class);
		lenient().when(dm.getId()).thenReturn(id);
		lenient().when(dm.getConversation()).thenReturn(conversation);
		lenient().when(dm.getCreatedAt()).thenReturn(LocalDateTime.now());
		lenient().when(dm.getSender()).thenReturn(sender);
		lenient().when(dm.getReceiver()).thenReturn(receiver);
		lenient().when(dm.getContent()).thenReturn("내용");
		return dm;
	}

	@Nested
	@DisplayName("DM 목록 조회")
	class GetDirectMessages {

		@Test
		@DisplayName("존재하지 않는 대화면 예외가 발생한다")
		void conversationNotFound_throws() {
			when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> directMessageService.getDirectMessages(
				conversationId, requesterId, null, null, 20,
				SortDirection.DESCENDING, DirectMessageSortBy.CREATED_AT
			)).isInstanceOf(ConversationNotFoundException.class);

			verifyNoInteractions(directMessageRepository);
		}

		@Test
		@DisplayName("당사자가 아니면 예외가 발생한다")
		void notParticipant_throws() {
			UUID otherUserId = UUID.randomUUID();
			when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

			assertThatThrownBy(() -> directMessageService.getDirectMessages(
				conversationId, otherUserId, null, null, 20,
				SortDirection.DESCENDING, DirectMessageSortBy.CREATED_AT
			)).isInstanceOf(ConversationAccessDeniedException.class);
		}

		@Test
		@DisplayName("다음 페이지가 있으면 hasNext=true를 반환한다")
		void success_hasNextTrue() {
			DirectMessage dm = mockDirectMessage(UUID.randomUUID(), requester, opponent);
			List<DirectMessage> rows = List.of(dm, dm, dm);

			when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
			when(directMessageRepository.findByConversationCursor(
				eq(conversationId), any(), any(), eq(3), eq(SortDirection.DESCENDING)
			)).thenReturn(rows);
			when(directMessageRepository.countByConversation(conversationId)).thenReturn(10L);

			CursorResponse<DirectMessageResponse> response = directMessageService.getDirectMessages(
				conversationId, requesterId, null, null, 2,
				SortDirection.DESCENDING, DirectMessageSortBy.CREATED_AT
			);

			assertThat(response.hasNext()).isTrue();
			assertThat(response.data()).hasSize(2);
			assertThat(response.totalCount()).isEqualTo(10L);
		}

		@Test
		@DisplayName("다음 페이지가 없으면 hasNext=false를 반환한다")
		void success_hasNextFalse() {
			DirectMessage dm = mockDirectMessage(UUID.randomUUID(), requester, opponent);

			when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
			when(directMessageRepository.findByConversationCursor(
				eq(conversationId), any(), any(), anyInt(), any()
			)).thenReturn(List.of(dm));
			when(directMessageRepository.countByConversation(conversationId)).thenReturn(1L);

			CursorResponse<DirectMessageResponse> response = directMessageService.getDirectMessages(
				conversationId, requesterId, null, null, 20,
				SortDirection.DESCENDING, DirectMessageSortBy.CREATED_AT
			);

			assertThat(response.hasNext()).isFalse();
			assertThat(response.nextCursor()).isNull();
			assertThat(response.nextIdAfter()).isNull();
		}

		@Test
		@DisplayName("limit이 0 이하이면 INVALID_INPUT_VALUE 예외가 발생한다")
		void invalidLimit_throws() {
			assertThatThrownBy(() -> directMessageService.getDirectMessages(
				conversationId,
				requesterId,
				null,
				null,
				0,
				SortDirection.DESCENDING,
				DirectMessageSortBy.CREATED_AT
			))
				.isInstanceOf(MoplException.class)
				.satisfies(exception ->
					assertThat(((MoplException) exception).getErrorCode())
						.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
				);

			assertThatThrownBy(() -> directMessageService.getDirectMessages(
				conversationId,
				requesterId,
				null,
				null,
				-1,
				SortDirection.DESCENDING,
				DirectMessageSortBy.CREATED_AT
			))
				.isInstanceOf(MoplException.class)
				.satisfies(exception ->
					assertThat(((MoplException) exception).getErrorCode())
						.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
				);

			verifyNoInteractions(conversationRepository, directMessageRepository);
		}

		@Test
		@DisplayName("cursor만 전달하면 INVALID_INPUT_VALUE 예외가 발생한다")
		void cursorOnly_throws() {
			assertThatThrownBy(() -> directMessageService.getDirectMessages(
				conversationId,
				requesterId,
				"2026-09-20T05:00:00",
				null,
				20,
				SortDirection.DESCENDING,
				DirectMessageSortBy.CREATED_AT
			))
				.isInstanceOf(MoplException.class)
				.satisfies(exception ->
					assertThat(((MoplException) exception).getErrorCode())
						.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
				);

			verifyNoInteractions(conversationRepository, directMessageRepository);
		}

		@Test
		@DisplayName("idAfter만 전달하면 INVALID_INPUT_VALUE 예외가 발생한다")
		void idAfterOnly_throws() {
			assertThatThrownBy(() -> directMessageService.getDirectMessages(
				conversationId,
				requesterId,
				null,
				UUID.randomUUID(),
				20,
				SortDirection.DESCENDING,
				DirectMessageSortBy.CREATED_AT
			))
				.isInstanceOf(MoplException.class)
				.satisfies(exception ->
					assertThat(((MoplException) exception).getErrorCode())
						.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
				);

			verifyNoInteractions(conversationRepository, directMessageRepository);
		}

		@Test
		@DisplayName("limit이 최대값을 초과하면 100개 기준으로 조회한다")
		void limitOverMax_isCappedAt100() {
			when(conversationRepository.findById(conversationId))
				.thenReturn(Optional.of(conversation));

			when(directMessageRepository.findByConversationCursor(
				eq(conversationId),
				isNull(),
				isNull(),
				eq(101),
				eq(SortDirection.DESCENDING)
			)).thenReturn(List.of());

			when(directMessageRepository.countByConversation(conversationId))
				.thenReturn(0L);

			directMessageService.getDirectMessages(
				conversationId,
				requesterId,
				null,
				null,
				1000,
				SortDirection.DESCENDING,
				DirectMessageSortBy.CREATED_AT
			);

			verify(directMessageRepository).findByConversationCursor(
				eq(conversationId),
				isNull(),
				isNull(),
				eq(101), // safeLimit 100 + 다음 페이지 확인용 1
				eq(SortDirection.DESCENDING)
			);
		}
	}

	@Nested
	@DisplayName("읽음 처리")
	class MarkAsRead {

		@Test
		@DisplayName("존재하지 않는 메시지면 예외가 발생한다")
		void notFound_throws() {
			UUID directMessageId = UUID.randomUUID();
			when(directMessageRepository.findById(directMessageId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> directMessageService.markAsRead(conversationId, directMessageId, requesterId))
				.isInstanceOf(DirectMessageNotFoundException.class);
		}

		@Test
		@DisplayName("경로의 conversationId와 메시지가 속한 대화가 다르면 예외가 발생한다")
		void conversationMismatch_throws() {
			UUID directMessageId = UUID.randomUUID();
			UUID otherConversationId = UUID.randomUUID();
			Conversation otherConversation = mock(Conversation.class);
			lenient().when(otherConversation.getId()).thenReturn(otherConversationId);

			DirectMessage dm = mock(DirectMessage.class);
			lenient().when(dm.getConversation()).thenReturn(otherConversation);
			when(directMessageRepository.findById(directMessageId)).thenReturn(Optional.of(dm));

			assertThatThrownBy(() -> directMessageService.markAsRead(conversationId, directMessageId, requesterId))
				.isInstanceOf(DirectMessageNotFoundException.class);

			verify(dm, never()).markAsRead();
		}

		@Test
		@DisplayName("수신자가 아니면 예외가 발생한다")
		void notReceiver_throws() {
			UUID directMessageId = UUID.randomUUID();
			DirectMessage dm = mockDirectMessage(directMessageId, requester, opponent);
			when(directMessageRepository.findById(directMessageId)).thenReturn(Optional.of(dm));

			// 이 메시지의 receiver는 opponent인데, requesterId로 읽음 처리 시도
			assertThatThrownBy(() -> directMessageService.markAsRead(conversationId, directMessageId, requesterId))
				.isInstanceOf(DirectMessageReadNotAllowedException.class);

			verify(dm, never()).markAsRead();
		}

		@Test
		@DisplayName("수신자 본인이면 읽음 처리한다")
		void success() {
			UUID directMessageId = UUID.randomUUID();
			DirectMessage dm = mockDirectMessage(directMessageId, opponent, requester);
			when(directMessageRepository.findById(directMessageId)).thenReturn(Optional.of(dm));

			directMessageService.markAsRead(conversationId, directMessageId, requesterId);

			verify(dm).markAsRead();
		}
	}
}