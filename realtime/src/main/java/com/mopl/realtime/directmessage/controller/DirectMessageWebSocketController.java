package com.mopl.realtime.directmessage.controller;

import com.mopl.realtime.directmessage.dto.DirectMessageResponse;
import com.mopl.realtime.directmessage.dto.DirectMessageSendRequest;
import com.mopl.realtime.directmessage.service.DirectMessageService;
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
public class DirectMessageWebSocketController {

	private final DirectMessageService directMessageService;
	private final SimpMessagingTemplate messagingTemplate;
	private final MeterRegistry meters;

	@MessageMapping("/conversations/{conversationId}/direct-messages")
	public void send(
		@DestinationVariable UUID conversationId,
		@Valid @Payload DirectMessageSendRequest request,
		Principal principal
	) {
		Timer.Sample sample = Timer.start(meters);
		String outcome = "error";
		try {
			UUID senderId = UUID.fromString(principal.getName());
	
			DirectMessageResponse response = directMessageService.send(
				conversationId,
				senderId,
				request.content()
			);
	
			messagingTemplate.convertAndSend(
				"/sub/conversations/" + conversationId + "/direct-messages",
				response
			);
			outcome = "success";
		} finally {
			sample.stop(meters.timer("realtime.message.send", "channel", "dm", "outcome", outcome));
		}
	}
}