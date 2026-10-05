package com.mopl.realtime.contentchat.controller;

import com.mopl.realtime.contentchat.dto.ContentChatResponse;
import com.mopl.realtime.contentchat.dto.ContentChatSendRequest;
import com.mopl.realtime.contentchat.service.ContentChatService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ContentChatWebSocketController {

	private final ContentChatService contentChatService;
	private final SimpMessagingTemplate messagingTemplate;
	private final MeterRegistry meters;

	@MessageMapping("/contents/{contentId}/chat")
	public void send(
		@DestinationVariable UUID contentId,
		@Valid @Payload ContentChatSendRequest request,
		Principal principal
	) {
		Timer.Sample sample = Timer.start(meters);
		String outcome = "error";
		try {
			UUID senderId = UUID.fromString(principal.getName());
	
			ContentChatResponse response = contentChatService.send(
				contentId,
				senderId,
				request.content()
			);
	
			messagingTemplate.convertAndSend(
				"/sub/contents/" + contentId + "/chat",
				response
			);
			outcome = "success";
		} finally {
			sample.stop(meters.timer("realtime.message.send", "channel", "chat", "outcome", outcome));
		}
	}
}