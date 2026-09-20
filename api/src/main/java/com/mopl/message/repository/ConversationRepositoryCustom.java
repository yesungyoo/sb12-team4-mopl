package com.mopl.message.repository;

import com.mopl.message.dto.SortDirection;

import java.util.List;
import java.util.UUID;

public interface ConversationRepositoryCustom {

	/**
	 * 메시지가 하나도 없는 대화방(lastDm == null)의 커서 값을 나타내는 마커.
	 * 실제 LocalDateTime 문자열과 겹치지 않도록 파싱 불가능한 값으로 둔다.
	 * ConversationService#getConversations() 의 nextCursor 생성 로직과 반드시 함께 맞춰야 한다.
	 */
	String NO_LAST_MESSAGE_CURSOR = "__NO_LAST_MESSAGE__";

	List<ConversationListRow> findConversationsByCursor(
		UUID requesterId,
		String keywordLike,
		String cursor,
		UUID idAfter,
		int limit,
		SortDirection sortDirection
	);

	long countConversations(UUID requesterId, String keywordLike);

	List<UUID> findConversationIdsWithUnread(UUID requesterId, List<UUID> conversationIds);
}