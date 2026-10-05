package com.mopl.realtime.contentchat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.core.common.enums.UserRole;
import com.mopl.realtime.contentchat.dto.ContentChatResponse;
import com.mopl.realtime.contentchat.dto.ContentChatSendRequest;
import com.mopl.realtime.contentchat.service.ContentChatService;
import com.mopl.realtime.global.security.StompPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class ContentChatWebSocketControllerTest {

	@Mock
	private ContentChatService contentChatService;

	@Mock
	private SimpMessagingTemplate messagingTemplate;

	@Mock
	private ContentChatResponse response;

	private ContentChatWebSocketController controller;
	private SimpleMeterRegistry meters;

	@BeforeEach
	void setUp() {
		meters = new SimpleMeterRegistry();
		controller = new ContentChatWebSocketController(
			contentChatService,
			messagingTemplate,
			meters
		);
	}

	@Nested
	@DisplayName("콘텐츠 채팅 전송")
	class SendContentChat {

		@Test
		@DisplayName("메시지를 저장하고 해당 콘텐츠 구독 채널로 브로드캐스트한다")
		void success() {
			UUID contentId = UUID.randomUUID();
			UUID senderId = UUID.randomUUID();

			StompPrincipal principal = new StompPrincipal(
				senderId,
				"user@test.com",
				UserRole.USER
			);

			ContentChatSendRequest request =
				new ContentChatSendRequest("hello");

			when(contentChatService.send(
				contentId,
				senderId,
				"hello"
			)).thenReturn(response);

			controller.send(
				contentId,
				request,
				principal
			);

			verify(contentChatService).send(
				contentId,
				senderId,
				"hello"
			);

			verify(messagingTemplate).convertAndSend(
				"/sub/contents/" + contentId + "/chat",
				response
			);

			assertThat(meters.get("realtime.message.send").tags("channel", "chat", "outcome", "success").timer().count()).isEqualTo(1);
			assertThat(meters.find("realtime.message.send").tag("outcome", "error").timer()).isNull();
		}
		@Test
		void serviceErrorIsMeasuredAndPropagated() {
			UUID targetId = UUID.randomUUID();
			UUID senderId = UUID.randomUUID();
			var principal = new StompPrincipal(senderId, "user@test.com", UserRole.USER);
			var failure = new IllegalStateException("service failed");
			when(contentChatService.send(targetId, senderId, "hello")).thenThrow(failure);

			assertThatThrownBy(() -> controller.send(targetId, new ContentChatSendRequest("hello"), principal))
				.isSameAs(failure);
			verifyNoInteractions(messagingTemplate);
			assertThat(meters.get("realtime.message.send").tags("channel", "chat", "outcome", "error").timer().count()).isEqualTo(1);
			assertThat(meters.find("realtime.message.send").tag("outcome", "success").timer()).isNull();
		}

		@Test
		void broadcastErrorIsMeasuredAndPropagated() {
			UUID targetId = UUID.randomUUID();
			UUID senderId = UUID.randomUUID();
			var principal = new StompPrincipal(senderId, "user@test.com", UserRole.USER);
			var failure = new IllegalStateException("broadcast failed");
			when(contentChatService.send(targetId, senderId, "hello")).thenReturn(response);
			doThrow(failure).when(messagingTemplate).convertAndSend("/sub/contents/" + targetId + "/chat", response);

			assertThatThrownBy(() -> controller.send(targetId, new ContentChatSendRequest("hello"), principal))
				.isSameAs(failure);
			assertThat(meters.get("realtime.message.send").tags("channel", "chat", "outcome", "error").timer().count()).isEqualTo(1);
			assertThat(meters.find("realtime.message.send").tag("outcome", "success").timer()).isNull();
		}

	}
}