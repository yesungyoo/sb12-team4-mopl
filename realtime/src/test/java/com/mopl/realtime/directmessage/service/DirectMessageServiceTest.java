package com.mopl.realtime.directmessage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.realtime.directmessage.dto.DirectMessageResponse;
import com.mopl.realtime.directmessage.repository.ConversationRepository;
import com.mopl.realtime.directmessage.repository.DirectMessageRepository;
import com.mopl.realtime.moderation.service.MessageModerationService;
import com.mopl.realtime.moderation.exception.ModerationException;
import com.mopl.core.common.enums.MessageType;
import com.mopl.realtime.moderation.dto.RuleAction;
import com.mopl.realtime.moderation.dto.RuleDecision;
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

@ExtendWith(MockitoExtension.class)
class DirectMessageServiceTest {

	@Mock
	private ConversationRepository conversationRepository;

	@Mock
	private DirectMessageRepository directMessageRepository;

	@Mock
	private Conversation conversation;

	@Mock
	private User user1;

	@Mock
	private User user2;

	@Mock
	private MessageModerationService moderation;

	private DirectMessageService directMessageService;

	private UUID conversationId;
	private UUID user1Id;
	private UUID user2Id;

	@BeforeEach
	void setUp() {
		directMessageService = new DirectMessageService(
			conversationRepository,
			directMessageRepository,
			moderation
		);

		conversationId = UUID.randomUUID();
		user1Id = UUID.randomUUID();
		user2Id = UUID.randomUUID();

		lenient().when(conversation.getId()).thenReturn(conversationId);
		lenient().when(conversation.getUser1()).thenReturn(user1);
		lenient().when(conversation.getUser2()).thenReturn(user2);

		lenient().when(user1.getId()).thenReturn(user1Id);
		lenient().when(user1.getName()).thenReturn("사용자1");
		lenient().when(user1.getProfileImageUrl()).thenReturn("http://user1.image");

		lenient().when(user2.getId()).thenReturn(user2Id);
		lenient().when(user2.getName()).thenReturn("사용자2");
		lenient().when(user2.getProfileImageUrl()).thenReturn("http://user2.image");
	}

	@Test
	void restrictedSenderIsRejectedBeforePersistence() {
		doThrow(new ModerationException(ModerationException.ErrorCode.CHAT_RESTRICTED))
			.when(moderation).assertCanSend(user1Id);
		assertThatThrownBy(() -> directMessageService.send(conversationId, user1Id, "hello"))
			.isInstanceOf(ModerationException.class);
		verifyNoInteractions(conversationRepository, directMessageRepository);
	}

	@Nested
	@DisplayName("DM 전송")
	class SendDirectMessage {

		@Test
		@DisplayName("user1이 메시지를 보내면 user2를 수신자로 저장한다")
		void success_fromUser1() {
			when(conversationRepository.findWithParticipantsById(conversationId))
				.thenReturn(Optional.of(conversation));
			when(moderation.inspect(any(), eq(MessageType.DM), eq(conversationId), anyString()))
                .thenAnswer(invocation -> new RuleDecision(RuleAction.ALLOW, invocation.getArgument(3, String.class)));
			when(directMessageRepository.save(any(DirectMessage.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

			DirectMessageResponse response = directMessageService.send(
				conversationId,
				user1Id,
				"hello"
			);

			ArgumentCaptor<DirectMessage> captor =
				ArgumentCaptor.forClass(DirectMessage.class);

			verify(directMessageRepository).save(captor.capture());

			DirectMessage saved = captor.getValue();

			assertThat(saved.getConversation()).isEqualTo(conversation);
			assertThat(saved.getSender()).isEqualTo(user1);
			assertThat(saved.getReceiver()).isEqualTo(user2);
			assertThat(saved.getContent()).isEqualTo("hello");

			assertThat(response.conversationId()).isEqualTo(conversationId);
			assertThat(response.sender().userId()).isEqualTo(user1Id);
			assertThat(response.receiver().userId()).isEqualTo(user2Id);
			assertThat(response.content()).isEqualTo("hello");
			verify(moderation).assertCanSend(user1Id);
			verify(moderation).inspect(user1Id, MessageType.DM, conversationId, "hello");
		}

		@Test
		@DisplayName("user2가 메시지를 보내면 user1을 수신자로 저장한다")
		void success_fromUser2() {
			when(conversationRepository.findWithParticipantsById(conversationId))
				.thenReturn(Optional.of(conversation));
			when(moderation.inspect(any(), eq(MessageType.DM), eq(conversationId), anyString()))
                .thenAnswer(invocation -> new RuleDecision(RuleAction.ALLOW, invocation.getArgument(3, String.class)));
			when(directMessageRepository.save(any(DirectMessage.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

			DirectMessageResponse response = directMessageService.send(
				conversationId,
				user2Id,
				"hi"
			);

			ArgumentCaptor<DirectMessage> captor =
				ArgumentCaptor.forClass(DirectMessage.class);

			verify(directMessageRepository).save(captor.capture());

			DirectMessage saved = captor.getValue();

			assertThat(saved.getSender()).isEqualTo(user2);
			assertThat(saved.getReceiver()).isEqualTo(user1);

			assertThat(response.sender().userId()).isEqualTo(user2Id);
			assertThat(response.receiver().userId()).isEqualTo(user1Id);
			assertThat(response.content()).isEqualTo("hi");
		}

		@Test
		@DisplayName("대화 참여자가 아니면 메시지를 보낼 수 없다")
		void notParticipant_throws() {
			UUID outsiderId = UUID.randomUUID();

			when(conversationRepository.findWithParticipantsById(conversationId))
				.thenReturn(Optional.of(conversation));

			assertThatThrownBy(() ->
				directMessageService.send(
					conversationId,
					outsiderId,
					"unauthorized"
				)
			)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Not a conversation participant");

			verify(directMessageRepository, never())
				.save(any(DirectMessage.class));
		}

		@Test
		@DisplayName("존재하지 않는 대화방이면 메시지를 보낼 수 없다")
		void conversationNotFound_throws() {
			when(conversationRepository.findWithParticipantsById(conversationId))
				.thenReturn(Optional.empty());

			assertThatThrownBy(() ->
				directMessageService.send(
					conversationId,
					user1Id,
					"hello"
				)
			)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Conversation not found");

			verify(directMessageRepository, never())
				.save(any(DirectMessage.class));
		}
	}
}
