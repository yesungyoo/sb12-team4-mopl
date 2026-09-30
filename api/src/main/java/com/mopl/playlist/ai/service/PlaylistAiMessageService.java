package com.mopl.playlist.ai.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.enums.PlaylistAiMessageRole;
import com.mopl.core.domain.playlist.entity.PlaylistAiMessage;
import com.mopl.core.domain.playlist.entity.PlaylistAiSession;
import com.mopl.playlist.ai.dto.AiPlaylistMessageResponse;
import com.mopl.playlist.ai.repository.PlaylistAiMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PlaylistAiMessageService {

	private static final int MAX_LIMIT = 100;

	private final PlaylistAiMessageRepository messageRepository;

	public PlaylistAiMessageService(
		PlaylistAiMessageRepository messageRepository
	) {
		this.messageRepository = messageRepository;
	}

	public List<PlaylistAiMessage> getRecentMessages(UUID sessionId) {
		List<PlaylistAiMessage> messages =
			messageRepository
				.findTop10BySession_IdOrderByCreatedAtDescIdDesc(
					sessionId
				);

		return messages.reversed();
	}

	public CursorResponse<AiPlaylistMessageResponse> getMessages(
		UUID sessionId,
		String cursor,
		String idAfter,
		int limit,
		String sortDirection
	) {
		if (limit <= 0) {
			throw new MoplException(
				CommonErrorCode.INVALID_INPUT_VALUE
			);
		}

		if ((cursor == null) != (idAfter == null)) {
			throw new MoplException(
				CommonErrorCode.INVALID_INPUT_VALUE
			);
		}

		boolean ascending;

		if ("ASCENDING".equalsIgnoreCase(sortDirection)) {
			ascending = true;
		} else if ("DESCENDING".equalsIgnoreCase(sortDirection)) {
			ascending = false;
		} else {
			throw new MoplException(
				CommonErrorCode.INVALID_INPUT_VALUE
			);
		}

		UUID parsedIdAfter = null;

		if (idAfter != null) {
			try {
				parsedIdAfter = UUID.fromString(idAfter);
			} catch (IllegalArgumentException e) {
				throw new MoplException(
					CommonErrorCode.INVALID_INPUT_VALUE
				);
			}
		}

		int safeLimit = Math.min(limit, MAX_LIMIT);

		List<PlaylistAiMessage> rows =
			messageRepository.findBySessionCursor(
				sessionId,
				cursor,
				parsedIdAfter,
				safeLimit + 1,
				ascending
			);

		boolean hasNext = rows.size() > safeLimit;

		List<PlaylistAiMessage> page = hasNext
			? rows.subList(0, safeLimit)
			: rows;

		List<AiPlaylistMessageResponse> data = page.stream()
			.map(message -> new AiPlaylistMessageResponse(
				message.getId(),
				message.getRole(),
				message.getContent(),
				message.getCreatedAt()
			))
			.toList();

		long totalCount =
			messageRepository.countBySession(sessionId);

		String nextCursor = null;
		String nextIdAfter = null;

		if (hasNext && !page.isEmpty()) {
			PlaylistAiMessage lastRow =
				page.getLast();

			nextCursor = lastRow.getCreatedAt()
				.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

			nextIdAfter = lastRow.getId().toString();
		}

		return CursorResponse.of(
			data,
			nextCursor,
			nextIdAfter,
			hasNext,
			totalCount,
			"CREATED_AT",
			sortDirection.toUpperCase()
		);
	}

	@Transactional
	public PlaylistAiMessage saveMessage(
		PlaylistAiSession session,
		PlaylistAiMessageRole role,
		String content
	) {
		return messageRepository.save(
			new PlaylistAiMessage(session, role, content)
		);
	}

	@Transactional
	public void deleteMessage(UUID messageId) {
		messageRepository.deleteById(messageId);
	}
}