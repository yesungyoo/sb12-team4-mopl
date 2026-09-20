package com.mopl.message.repository;

import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.message.dto.SortDirection;

import java.util.List;
import java.util.UUID;

public interface DirectMessageRepositoryCustom {

	List<DirectMessage> findByConversationCursor(
		UUID conversationId,
		String cursor,
		UUID idAfter,
		int limit,
		SortDirection sortDirection
	);

	long countByConversation(UUID conversationId);
}