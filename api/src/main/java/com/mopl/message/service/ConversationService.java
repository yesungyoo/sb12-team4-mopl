package com.mopl.message.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.message.ConversationAccessDeniedException;
import com.mopl.common.exception.message.ConversationNotFoundException;
import com.mopl.common.exception.message.ConversationSelfNotAllowedException;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.message.dto.ConversationResponse;
import com.mopl.message.dto.ConversationSortBy;
import com.mopl.message.dto.CursorResponse;
import com.mopl.message.dto.SortDirection;
import com.mopl.message.repository.ConversationListRow;
import com.mopl.message.repository.ConversationRepository;
import com.mopl.message.repository.ConversationRepositoryCustom;
import com.mopl.message.repository.DirectMessageRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ConversationService {

	private static final int MAX_LIMIT = 100;

	private final ConversationRepository conversationRepository;
	private final DirectMessageRepository directMessageRepository;

	public ConversationService(
		ConversationRepository conversationRepository,
		DirectMessageRepository directMessageRepository
	) {
		this.conversationRepository = conversationRepository;
		this.directMessageRepository = directMessageRepository;
	}

	@Transactional
	public Conversation getOrCreateConversation(User requester, User target) {
		if (requester.getId().equals(target.getId())) {
			throw new ConversationSelfNotAllowedException();
		}

		boolean requesterFirst = comesBefore(requester.getId(), target.getId());

		User user1 = requesterFirst ? requester : target;
		User user2 = requesterFirst ? target : requester;

		return conversationRepository.findByUser1_IdAndUser2_Id(user1.getId(), user2.getId())
			.orElseGet(() -> conversationRepository.save(new Conversation(user1, user2)));
	}

	public ConversationResponse getConversation(UUID conversationId, UUID requesterId) {
		Conversation conversation = conversationRepository.findById(conversationId)
			.orElseThrow(ConversationNotFoundException::new);

		validateParticipant(conversation, requesterId);

		return toConversationResponse(conversation, requesterId);
	}

	public ConversationResponse getConversationWith(UUID requesterId, UUID targetUserId) {
		boolean requesterFirst = comesBefore(requesterId, targetUserId);

		UUID smallerId = requesterFirst ? requesterId : targetUserId;
		UUID largerId = requesterFirst ? targetUserId : requesterId;

		Conversation conversation = conversationRepository.findByUser1_IdAndUser2_Id(smallerId, largerId)
			.orElseThrow(ConversationNotFoundException::new);

		return toConversationResponse(conversation, requesterId);
	}

	public CursorResponse<ConversationResponse> getConversations(
		UUID requesterId,
		String keywordLike,
		String cursor,
		UUID idAfter,
		int limit,
		SortDirection sortDirection,
		ConversationSortBy sortBy
	) {
		if (limit <= 0) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
		if ((cursor == null) != (idAfter == null)) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		int safeLimit = Math.min(limit, MAX_LIMIT);

		List<ConversationListRow> rows = conversationRepository.findConversationsByCursor(
			requesterId, keywordLike, cursor, idAfter, safeLimit + 1, sortDirection
		);

		boolean hasNext = rows.size() > safeLimit;
		List<ConversationListRow> page = hasNext
			? rows.subList(0, safeLimit)
			: rows;

		Set<UUID> unreadConversationIds = Set.copyOf(
			conversationRepository.findConversationIdsWithUnread(
				requesterId,
				page.stream()
					.map(row -> row.conversation().getId())
					.toList()
			)
		);

		List<ConversationResponse> data = page.stream()
			.map(row -> ConversationResponse.from(
				row.conversation(),
				row.lastMessage(),
				requesterId,
				unreadConversationIds.contains(row.conversation().getId())
			))
			.toList();

		long totalCount =
			conversationRepository.countConversations(requesterId, keywordLike);

		String nextCursor = null;
		UUID nextIdAfter = null;

		if (hasNext && !page.isEmpty()) {
			ConversationListRow lastRow = page.get(page.size() - 1);

			nextCursor = lastRow.lastMessage() == null
				? ConversationRepositoryCustom.NO_LAST_MESSAGE_CURSOR
				: lastRow.lastMessage()
					.getCreatedAt()
					.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

			nextIdAfter = lastRow.conversation().getId();
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

	private void validateParticipant(Conversation conversation, UUID requesterId) {
		boolean isParticipant = conversation.getUser1().getId().equals(requesterId)
			|| conversation.getUser2().getId().equals(requesterId);

		if (!isParticipant) {
			throw new ConversationAccessDeniedException();
		}
	}

	private ConversationResponse toConversationResponse(Conversation conversation, UUID requesterId) {
		DirectMessage lastMessage = directMessageRepository
			.findTopByConversation_IdOrderByCreatedAtDesc(conversation.getId())
			.orElse(null);

		boolean hasUnread = directMessageRepository
			.existsByConversation_IdAndReceiver_IdAndReadAtIsNull(conversation.getId(), requesterId);

		return ConversationResponse.from(conversation, lastMessage, requesterId, hasUnread);
	}

	private static boolean comesBefore(UUID first, UUID second) {
		return first.toString().compareTo(second.toString()) < 0;
	}
}