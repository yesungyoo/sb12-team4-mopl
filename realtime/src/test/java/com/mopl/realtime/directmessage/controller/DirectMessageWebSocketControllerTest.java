package com.mopl.realtime.directmessage.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.core.common.enums.UserRole;
import com.mopl.realtime.directmessage.dto.DirectMessageResponse;
import com.mopl.realtime.directmessage.dto.DirectMessageSendRequest;
import com.mopl.realtime.directmessage.service.DirectMessageService;
import com.mopl.realtime.global.security.StompPrincipal;
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
class DirectMessageWebSocketControllerTest {

	@Mock
	private DirectMessageService directMessageService;

	@Mock
	private SimpMessagingTemplate messagingTemplate;

	@Mock
	private DirectMessageResponse response;

	private DirectMessageWebSocketController controller;

	@BeforeEach
	void setUp() {
		controller = new DirectMessageWebSocketController(
			directMessageService,
			messagingTemplate
		);
	}

	@Nested
	@DisplayName("DM 전송")
	class SendDirectMessage {

		@Test
		@DisplayName("메시지를 저장한 뒤 해당 대화방 구독 채널로 브로드캐스트한다")
		void success() {
			UUID conversationId = UUID.randomUUID();
			UUID senderId = UUID.randomUUID();

			StompPrincipal principal = new StompPrincipal(
				senderId,
				"user@test.com",
				UserRole.USER
			);

			DirectMessageSendRequest request =
				new DirectMessageSendRequest("hello");

			when(directMessageService.send(
				conversationId,
				senderId,
				"hello"
			)).thenReturn(response);

			controller.send(
				conversationId,
				request,
				principal
			);

			verify(directMessageService).send(
				conversationId,
				senderId,
				"hello"
			);

			verify(messagingTemplate).convertAndSend(
				"/sub/conversations/"
					+ conversationId
					+ "/direct-messages",
				response
			);
		}
	}
}