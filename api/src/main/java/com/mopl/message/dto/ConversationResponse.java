package com.mopl.message.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.user.entity.User;

import java.util.UUID;

public record ConversationResponse(
	UUID id,
	UserSummary with,
	@JsonProperty("lastestMessage") DirectMessageResponse latestMessage,
	boolean hasUnread
) {
	public static ConversationResponse from(
		Conversation conversation,
		DirectMessage lastMessage,
		UUID requesterId,
		boolean hasUnread
	) {
		User opponent = conversation.getUser1().getId().equals(requesterId)
			? conversation.getUser2()
			: conversation.getUser1();

		return new ConversationResponse(
			conversation.getId(),
			UserSummary.from(opponent),
			lastMessage == null ? null : DirectMessageResponse.from(lastMessage),
			hasUnread
		);
	}
}