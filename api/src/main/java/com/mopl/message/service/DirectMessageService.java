package com.mopl.message.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.message.ConversationAccessDeniedException;
import com.mopl.common.exception.message.ConversationNotFoundException;
import com.mopl.common.exception.message.DirectMessageNotFoundException;
import com.mopl.common.exception.message.DirectMessageReadNotAllowedException;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.message.dto.CursorResponse;
import com.mopl.message.dto.DirectMessageResponse;
import com.mopl.message.dto.DirectMessageSortBy;
import com.mopl.message.dto.SortDirection;
import com.mopl.message.repository.ConversationRepository;
import com.mopl.message.repository.DirectMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DirectMessageService {

	private static final int MAX_LIMIT = 100;

	private final DirectMessageRepository directMessageRepository;
	private final ConversationRepository conversationRepository;

	public DirectMessageService(
		DirectMessageRepository directMessageRepository,
		ConversationRepository conversationRepository
	) {
		this.directMessageRepository = directMessageRepository;
		this.conversationRepository = conversationRepository;
	}

	public CursorResponse<DirectMessageResponse> getDirectMessages(
		UUID conversationId,
		UUID requesterId,
		String cursor,
		UUID idAfter,
		int limit,
		SortDirection sortDirection,
		DirectMessageSortBy sortBy
	) {
		if (limit <= 0) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
		if ((cursor == null) != (idAfter == null)) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		int safeLimit = Math.min(limit, MAX_LIMIT);

		Conversation conversation = conversationRepository.findById(conversationId)
			.orElseThrow(ConversationNotFoundException::new);

		validateParticipant(conversation, requesterId);

		List<DirectMessage> rows = directMessageRepository.findByConversationCursor(
			conversationId, cursor, idAfter, safeLimit + 1, sortDirection
		);

		boolean hasNext = rows.size() > safeLimit;
		List<DirectMessage> page = hasNext
			? rows.subList(0, safeLimit)
			: rows;

		List<DirectMessageResponse> data = page.stream()
			.map(DirectMessageResponse::from)
			.toList();

		long totalCount = directMessageRepository.countByConversation(conversationId);

		String nextCursor = null;
		UUID nextIdAfter = null;

		if (hasNext && !page.isEmpty()) {
			DirectMessage lastRow = page.get(page.size() - 1);
			nextCursor = lastRow.getCreatedAt()
				.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
			nextIdAfter = lastRow.getId();
		}

		return new CursorResponse<>(
			data,
			nextCursor,
			nextIdAfter,
			hasNext,
			totalCount,
			sortBy.name(),
			sortDirection.name()
		);
	}

	@Transactional
	public void markAsRead(UUID conversationId, UUID directMessageId, UUID requesterId) {
		DirectMessage directMessage = directMessageRepository.findById(directMessageId)
			.orElseThrow(DirectMessageNotFoundException::new);

		if (!directMessage.getConversation().getId().equals(conversationId)) {
			throw new DirectMessageNotFoundException();
		}

		if (!directMessage.getReceiver().getId().equals(requesterId)) {
			throw new DirectMessageReadNotAllowedException();
		}

		directMessage.markAsRead();
	}

	private void validateParticipant(Conversation conversation, UUID requesterId) {
		boolean isParticipant = conversation.getUser1().getId().equals(requesterId)
			|| conversation.getUser2().getId().equals(requesterId);

		if (!isParticipant) {
			throw new ConversationAccessDeniedException();
		}
	}
}