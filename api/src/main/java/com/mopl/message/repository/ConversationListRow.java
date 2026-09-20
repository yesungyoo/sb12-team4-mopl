package com.mopl.message.repository;

import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;

public record ConversationListRow(
	Conversation conversation,
	DirectMessage lastMessage
) {
}