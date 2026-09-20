package com.mopl.message.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.message.ConversationAccessDeniedException;
import com.mopl.common.exception.message.ConversationNotFoundException;
import com.mopl.common.exception.message.ConversationSelfNotAllowedException;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.message.dto.ConversationResponse;
import com.mopl.message.dto.ConversationSortBy;
import com.mopl.message.dto.CursorResponse;
import com.mopl.message.dto.SortDirection;
import com.mopl.message.repository.ConversationListRow;
import com.mopl.message.repository.ConversationRepository;
import com.mopl.message.repository.ConversationRepositoryCustom;
import com.mopl.message.repository.DirectMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class ConversationServiceTest {

	@Mock
	private ConversationRepository conversationRepository;

	@Mock
	private DirectMessageRepository directMessageRepository;

	private ConversationService conversationService;

	private UUID smallerId;
	private UUID largerId;
	private User smallerUser;
	private User largerUser;
	private UUID conversationId;
	private Conversation conversation;

	@BeforeEach
	void setUp() {
		conversationService = new ConversationService(conversationRepository, directMessageRepository);

		UUID idA = UUID.randomUUID();
		UUID idB = UUID.randomUUID();
		smallerId = idA.compareTo(idB) < 0 ? idA : idB;
		largerId = idA.compareTo(idB) < 0 ? idB : idA;

		smallerUser = mock(User.class);
		lenient().when(smallerUser.getId()).thenReturn(smallerId);
		lenient().when(smallerUser.getName()).thenReturn("작은유저");
		lenient().when(smallerUser.getProfileImageUrl()).thenReturn("http://image.url/a");

		largerUser = mock(User.class);
		lenient().when(largerUser.getId()).thenReturn(largerId);
		lenient().when(largerUser.getName()).thenReturn("큰유저");
		lenient().when(largerUser.getProfileImageUrl()).thenReturn("http://image.url/b");

		conversationId = UUID.randomUUID();
		conversation = mock(Conversation.class);
		lenient().when(conversation.getId()).thenReturn(conversationId);
		lenient().when(conversation.getUser1()).thenReturn(smallerUser);
		lenient().when(conversation.getUser2()).thenReturn(largerUser);
	}

	@Nested
	@DisplayName("생성/조회")
	class GetOrCreateConversation {

		@Test
		@DisplayName("자기 자신과의 대화를 생성하려 하면 예외가 발생한다")
		void self_throws() {
			assertThatThrownBy(() -> conversationService.getOrCreateConversation(smallerUser, smallerUser))
				.isInstanceOf(ConversationSelfNotAllowedException.class);

			verifyNoInteractions(conversationRepository);
		}

		@Test
		@DisplayName("UUID가 작은 쪽이 user1, 큰 쪽이 user2로 저장된다")
		void ordersUsersByUuid() {
			when(conversationRepository.findByUser1_IdAndUser2_Id(smallerId, largerId))
				.thenReturn(Optional.empty());
			when(conversationRepository.save(any(Conversation.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

			ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);

			// requester/target 순서를 뒤집어서 호출해도 결과는 동일해야 한다
			conversationService.getOrCreateConversation(largerUser, smallerUser);

			verify(conversationRepository).save(captor.capture());
			Conversation saved = captor.getValue();

			assertThat(saved.getUser1()).isEqualTo(smallerUser);
			assertThat(saved.getUser2()).isEqualTo(largerUser);
		}

		@Test
		@DisplayName("이미 존재하는 대화면 새로 생성하지 않고 기존 대화를 반환한다")
		void alreadyExists_returnsExisting() {
			when(conversationRepository.findByUser1_IdAndUser2_Id(smallerId, largerId))
				.thenReturn(Optional.of(conversation));

			Conversation result = conversationService.getOrCreateConversation(smallerUser, largerUser);

			assertThat(result).isEqualTo(conversation);
			verify(conversationRepository, never()).save(any());
		}
	}

	@Nested
	@DisplayName("단건 조회")
	class GetConversation {

		@Test
		@DisplayName("존재하지 않는 대화를 조회하면 예외가 발생한다")
		void notFound_throws() {
			when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> conversationService.getConversation(conversationId, smallerId))
				.isInstanceOf(ConversationNotFoundException.class);
		}

		@Test
		@DisplayName("당사자가 아니면 예외가 발생한다")
		void notParticipant_throws() {
			UUID otherUserId = UUID.randomUUID();
			when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

			assertThatThrownBy(() -> conversationService.getConversation(conversationId, otherUserId))
				.isInstanceOf(ConversationAccessDeniedException.class);
		}

		@Test
		@DisplayName("요청자가 user1이면 상대방(with)은 user2다")
		void success_opponentIsUser2() {
			when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
			when(directMessageRepository.findTopByConversation_IdOrderByCreatedAtDesc(conversationId))
				.thenReturn(Optional.empty());
			when(directMessageRepository.existsByConversation_IdAndReceiver_IdAndReadAtIsNull(conversationId, smallerId))
				.thenReturn(false);

			ConversationResponse response = conversationService.getConversation(conversationId, smallerId);

			assertThat(response.with().userId()).isEqualTo(largerId);
			assertThat(response.latestMessage()).isNull();
			assertThat(response.hasUnread()).isFalse();
		}

		@Test
		@DisplayName("메시지가 하나도 없는 대화방을 조회하면 latestMessage는 null이다")
		void success_noMessages_latestMessageNull() {
			when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
			when(directMessageRepository.findTopByConversation_IdOrderByCreatedAtDesc(conversationId))
				.thenReturn(Optional.empty());
			when(directMessageRepository.existsByConversation_IdAndReceiver_IdAndReadAtIsNull(conversationId, smallerId))
				.thenReturn(false);

			ConversationResponse response = conversationService.getConversation(conversationId, smallerId);

			assertThat(response.latestMessage()).isNull();
		}
	}

	@Nested
	@DisplayName("특정 사용자와의 대화 조회")
	class GetConversationWith {

		@Test
		@DisplayName("존재하지 않는 대화면 예외가 발생한다")
		void notFound_throws() {
			when(conversationRepository.findByUser1_IdAndUser2_Id(smallerId, largerId))
				.thenReturn(Optional.empty());

			assertThatThrownBy(() -> conversationService.getConversationWith(smallerId, largerId))
				.isInstanceOf(ConversationNotFoundException.class);
		}

		@Test
		@DisplayName("요청자 ID 순서와 무관하게 동일한 대화를 조회한다")
		void success_orderIndependent() {
			when(conversationRepository.findByUser1_IdAndUser2_Id(smallerId, largerId))
				.thenReturn(Optional.of(conversation));
			when(directMessageRepository.findTopByConversation_IdOrderByCreatedAtDesc(conversationId))
				.thenReturn(Optional.empty());
			when(directMessageRepository.existsByConversation_IdAndReceiver_IdAndReadAtIsNull(conversationId, largerId))
				.thenReturn(false);

			ConversationResponse response = conversationService.getConversationWith(largerId, smallerId);

			assertThat(response.id()).isEqualTo(conversationId);
			assertThat(response.with().userId()).isEqualTo(smallerId);
		}
	}

	@Nested
	@DisplayName("목록 조회")
	class GetConversations {

		@Test
		@DisplayName("다음 페이지가 있으면 hasNext=true와 nextCursor를 반환한다")
		void success_hasNextTrue() {
			DirectMessage lastMessage = mock(DirectMessage.class);
			lenient().when(lastMessage.getId()).thenReturn(UUID.randomUUID());
			lenient().when(lastMessage.getConversation()).thenReturn(conversation);
			lenient().when(lastMessage.getCreatedAt()).thenReturn(LocalDateTime.now());
			lenient().when(lastMessage.getSender()).thenReturn(smallerUser);
			lenient().when(lastMessage.getReceiver()).thenReturn(largerUser);
			lenient().when(lastMessage.getContent()).thenReturn("내용");

			ConversationListRow row = new ConversationListRow(conversation, lastMessage);
			List<ConversationListRow> rows = List.of(row, row, row);

			when(conversationRepository.findConversationsByCursor(
				eq(smallerId), any(), any(), any(), eq(3), eq(SortDirection.DESCENDING)
			)).thenReturn(rows);
			when(conversationRepository.findConversationIdsWithUnread(eq(smallerId), any()))
				.thenReturn(List.of());
			when(conversationRepository.countConversations(smallerId, null)).thenReturn(10L);

			CursorResponse<ConversationResponse> response = conversationService.getConversations(
				smallerId, null, null, null, 2, SortDirection.DESCENDING, ConversationSortBy.CREATED_AT
			);

			assertThat(response.hasNext()).isTrue();
			assertThat(response.data()).hasSize(2);
			assertThat(response.totalCount()).isEqualTo(10L);
		}

		@Test
		@DisplayName("읽지 않은 메시지가 있는 대화는 hasUnread=true로 반환한다")
		void success_hasUnreadTrue() {
			DirectMessage lastMessage = mock(DirectMessage.class);
			lenient().when(lastMessage.getId()).thenReturn(UUID.randomUUID());
			lenient().when(lastMessage.getConversation()).thenReturn(conversation);
			lenient().when(lastMessage.getCreatedAt()).thenReturn(LocalDateTime.now());
			lenient().when(lastMessage.getSender()).thenReturn(largerUser);
			lenient().when(lastMessage.getReceiver()).thenReturn(smallerUser);
			lenient().when(lastMessage.getContent()).thenReturn("내용");

			ConversationListRow row = new ConversationListRow(conversation, lastMessage);

			when(conversationRepository.findConversationsByCursor(
				eq(smallerId), any(), any(), any(), anyInt(), any()
			)).thenReturn(List.of(row));
			when(conversationRepository.findConversationIdsWithUnread(eq(smallerId), any()))
				.thenReturn(List.of(conversationId));
			when(conversationRepository.countConversations(smallerId, null)).thenReturn(1L);

			CursorResponse<ConversationResponse> response = conversationService.getConversations(
				smallerId, null, null, null, 20, SortDirection.DESCENDING, ConversationSortBy.CREATED_AT
			);

			assertThat(response.data().get(0).hasUnread()).isTrue();
		}

		@Test
		@DisplayName("다음 페이지가 있고 마지막 대화에 메시지가 없으면 빈 대화방 전용 커서를 반환한다")
		void success_noLastMessage_returnsNoLastMessageCursor() {
			Conversation conversation1 = mock(Conversation.class);
			UUID conversationId1 = UUID.randomUUID();
			lenient().when(conversation1.getId()).thenReturn(conversationId1);
			lenient().when(conversation1.getUser1()).thenReturn(smallerUser);
			lenient().when(conversation1.getUser2()).thenReturn(largerUser);

			Conversation conversation2 = mock(Conversation.class);
			UUID conversationId2 = UUID.randomUUID();
			lenient().when(conversation2.getId()).thenReturn(conversationId2);
			lenient().when(conversation2.getUser1()).thenReturn(smallerUser);
			lenient().when(conversation2.getUser2()).thenReturn(largerUser);

			ConversationListRow firstRow =
				new ConversationListRow(conversation1, null);
			ConversationListRow secondRow =
				new ConversationListRow(conversation2, null);

			when(conversationRepository.findConversationsByCursor(
				eq(smallerId),
				isNull(),
				isNull(),
				isNull(),
				eq(2),
				eq(SortDirection.DESCENDING)
			)).thenReturn(List.of(firstRow, secondRow));

			when(conversationRepository.findConversationIdsWithUnread(
				eq(smallerId), any()
			)).thenReturn(List.of());

			when(conversationRepository.countConversations(smallerId, null))
				.thenReturn(2L);

			CursorResponse<ConversationResponse> response =
				conversationService.getConversations(
					smallerId,
					null,
					null,
					null,
					1,
					SortDirection.DESCENDING,
					ConversationSortBy.CREATED_AT
				);

			assertThat(response.hasNext()).isTrue();
			assertThat(response.nextCursor())
				.isEqualTo(ConversationRepositoryCustom.NO_LAST_MESSAGE_CURSOR);
			assertThat(response.nextIdAfter()).isEqualTo(conversationId1);
			assertThat(response.data()).hasSize(1);
			assertThat(response.data().get(0).latestMessage()).isNull();
		}

		@Test
		@DisplayName("limit이 0 이하이면 INVALID_INPUT_VALUE 예외가 발생한다")
		void invalidLimit_throws() {
			assertThatThrownBy(() -> conversationService.getConversations(
				smallerId,
				null,
				null,
				null,
				0,
				SortDirection.DESCENDING,
				ConversationSortBy.CREATED_AT
			))
				.isInstanceOf(MoplException.class)
				.satisfies(exception ->
					assertThat(((MoplException) exception).getErrorCode())
						.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
				);

			assertThatThrownBy(() -> conversationService.getConversations(
				smallerId,
				null,
				null,
				null,
				-1,
				SortDirection.DESCENDING,
				ConversationSortBy.CREATED_AT
			))
				.isInstanceOf(MoplException.class)
				.satisfies(exception ->
					assertThat(((MoplException) exception).getErrorCode())
						.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
				);

			verifyNoInteractions(conversationRepository);
		}

		@Test
		@DisplayName("cursor만 전달하면 INVALID_INPUT_VALUE 예외가 발생한다")
		void cursorOnly_throws() {
			assertThatThrownBy(() -> conversationService.getConversations(
				smallerId,
				null,
				"2026-09-20T05:00:00",
				null,
				20,
				SortDirection.DESCENDING,
				ConversationSortBy.CREATED_AT
			))
				.isInstanceOf(MoplException.class)
				.satisfies(exception ->
					assertThat(((MoplException) exception).getErrorCode())
						.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
				);

			verifyNoInteractions(conversationRepository);
		}

		@Test
		@DisplayName("idAfter만 전달하면 INVALID_INPUT_VALUE 예외가 발생한다")
		void idAfterOnly_throws() {
			assertThatThrownBy(() -> conversationService.getConversations(
				smallerId,
				null,
				null,
				UUID.randomUUID(),
				20,
				SortDirection.DESCENDING,
				ConversationSortBy.CREATED_AT
			))
				.isInstanceOf(MoplException.class)
				.satisfies(exception ->
					assertThat(((MoplException) exception).getErrorCode())
						.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
				);

			verifyNoInteractions(conversationRepository);
		}

		@Test
		@DisplayName("limit이 최대값을 초과하면 100개 기준으로 조회한다")
		void limitOverMax_isCappedAt100() {
			when(conversationRepository.findConversationsByCursor(
				eq(smallerId),
				isNull(),
				isNull(),
				isNull(),
				eq(101),
				eq(SortDirection.DESCENDING)
			)).thenReturn(List.of());

			when(conversationRepository.findConversationIdsWithUnread(
				eq(smallerId), any()
			)).thenReturn(List.of());

			when(conversationRepository.countConversations(smallerId, null))
				.thenReturn(0L);

			conversationService.getConversations(
				smallerId,
				null,
				null,
				null,
				1000,
				SortDirection.DESCENDING,
				ConversationSortBy.CREATED_AT
			);

			verify(conversationRepository).findConversationsByCursor(
				eq(smallerId),
				isNull(),
				isNull(),
				isNull(),
				eq(101), // safeLimit 100 + hasNext 확인용 1
				eq(SortDirection.DESCENDING)
			);
		}
	}
}