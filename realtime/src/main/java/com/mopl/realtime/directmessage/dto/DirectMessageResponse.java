package com.mopl.realtime.directmessage.dto;

import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.realtime.common.dto.UserSummary;

import java.time.LocalDateTime;
import java.util.UUID;

public record DirectMessageResponse(
	UUID id,
	UUID conversationId,
	LocalDateTime createdAt,
	UserSummary sender,
	UserSummary receiver,
	String content
) {

	public static DirectMessageResponse from(DirectMessage directMessage) {
		return new DirectMessageResponse(
			directMessage.getId(),
			directMessage.getConversation().getId(),
			directMessage.getCreatedAt(),
			UserSummary.from(directMessage.getSender()),
			UserSummary.from(directMessage.getReceiver()),
			directMessage.getContent()
		);
	}
}