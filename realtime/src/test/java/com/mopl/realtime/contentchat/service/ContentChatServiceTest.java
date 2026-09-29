package com.mopl.realtime.contentchat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.message.entity.ContentChatMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.realtime.contentchat.dto.ContentChatResponse;
import com.mopl.realtime.contentchat.repository.ContentChatMessageRepository;
import com.mopl.realtime.contentchat.repository.ContentRepository;
import com.mopl.realtime.contentchat.repository.UserRepository;
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
class ContentChatServiceTest {

	@Mock
	private ContentRepository contentRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private ContentChatMessageRepository contentChatMessageRepository;

	@Mock
	private Content content;

	@Mock
	private User sender;

	private ContentChatService contentChatService;

	private UUID contentId;
	private UUID senderId;

	@BeforeEach
	void setUp() {
		contentChatService = new ContentChatService(
			contentRepository,
			userRepository,
			contentChatMessageRepository
		);

		contentId = UUID.randomUUID();
		senderId = UUID.randomUUID();

		lenient().when(sender.getId()).thenReturn(senderId);
		lenient().when(sender.getName()).thenReturn("사용자");
		lenient().when(sender.getProfileImageUrl()).thenReturn("http://profile.image");
	}

	@Nested
	@DisplayName("콘텐츠 채팅 전송")
	class SendContentChat {

		@Test
		@DisplayName("메시지를 저장하고 응답을 반환한다")
		void success() {
			when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
				.thenReturn(Optional.of(content));
			when(userRepository.findByIdAndDeletedAtIsNull(senderId))
				.thenReturn(Optional.of(sender));
			when(contentChatMessageRepository.save(any(ContentChatMessage.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

			ContentChatResponse response = contentChatService.send(
				contentId,
				senderId,
				"hello"
			);

			ArgumentCaptor<ContentChatMessage> captor =
				ArgumentCaptor.forClass(ContentChatMessage.class);

			verify(contentChatMessageRepository).save(captor.capture());

			ContentChatMessage saved = captor.getValue();

			assertThat(saved.getContent()).isEqualTo(content);
			assertThat(saved.getSender()).isEqualTo(sender);
			assertThat(saved.getMessage()).isEqualTo("hello");

			assertThat(response.sender().userId()).isEqualTo(senderId);
			assertThat(response.content()).isEqualTo("hello");
		}

		@Test
		@DisplayName("존재하지 않는 콘텐츠에는 메시지를 보낼 수 없다")
		void contentNotFound_throws() {
			when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
				.thenReturn(Optional.empty());

			assertThatThrownBy(() ->
				contentChatService.send(
					contentId,
					senderId,
					"hello"
				)
			)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Content not found");

			verify(contentChatMessageRepository, never())
				.save(any(ContentChatMessage.class));
		}

		@Test
		@DisplayName("존재하지 않는 사용자는 메시지를 보낼 수 없다")
		void userNotFound_throws() {
			when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
				.thenReturn(Optional.of(content));
			when(userRepository.findByIdAndDeletedAtIsNull(senderId))
				.thenReturn(Optional.empty());

			assertThatThrownBy(() ->
				contentChatService.send(
					contentId,
					senderId,
					"hello"
				)
			)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("User not found");

			verify(contentChatMessageRepository, never())
				.save(any(ContentChatMessage.class));
		}
	}
}