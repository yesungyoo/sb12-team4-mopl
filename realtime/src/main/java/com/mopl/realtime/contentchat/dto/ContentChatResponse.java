package com.mopl.realtime.contentchat.dto;

import com.mopl.core.domain.message.entity.ContentChatMessage;
import com.mopl.realtime.common.dto.UserSummary;

public record ContentChatResponse(
	UserSummary sender,
	String content
) {

	public static ContentChatResponse from(ContentChatMessage message) {
		return new ContentChatResponse(
			UserSummary.from(message.getSender()),
			message.getMessage()
		);
	}
}