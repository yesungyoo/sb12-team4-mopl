package com.mopl.playlist.ai.service;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.common.exception.CommonErrorCode;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.playlist.ai.dto.AiPlaylistSessionResponse;
import java.time.format.DateTimeFormatter;
import com.mopl.core.domain.playlist.entity.PlaylistAiSession;
import com.mopl.core.domain.user.entity.User;
import com.mopl.playlist.ai.repository.PlaylistAiSessionRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.common.exception.playlist.PlaylistAiSessionNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PlaylistAiSessionService {

	private static final int MAX_LIMIT = 100;

	private final PlaylistAiSessionRepository sessionRepository;
	private final UserRepository userRepository;

	public PlaylistAiSessionService(
		PlaylistAiSessionRepository sessionRepository,
		UserRepository userRepository
	) {
		this.sessionRepository = sessionRepository;
		this.userRepository = userRepository;
	}

	public PlaylistAiSession getSession(
		UUID sessionId,
		UUID userId
	) {
		return sessionRepository.findByIdAndUser_Id(sessionId, userId)
			.orElseThrow(PlaylistAiSessionNotFoundException::new);
	}

	public CursorResponse<AiPlaylistSessionResponse> getSessions(
		UUID userId,
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

		List<PlaylistAiSession> rows =
			sessionRepository.findByUserCursor(
				userId,
				cursor,
				parsedIdAfter,
				safeLimit + 1,
				ascending
			);

		boolean hasNext = rows.size() > safeLimit;

		List<PlaylistAiSession> page = hasNext
			? rows.subList(0, safeLimit)
			: rows;

		List<AiPlaylistSessionResponse> data = page.stream()
			.map(session -> new AiPlaylistSessionResponse(
				session.getId(),
				session.getTitle(),
				session.getCreatedAt(),
				session.getUpdatedAt()
			))
			.toList();

		long totalCount = sessionRepository.countByUser(userId);

		String nextCursor = null;
		String nextIdAfter = null;

		if (hasNext && !page.isEmpty()) {
			PlaylistAiSession lastRow = page.getLast();

			nextCursor = lastRow.getUpdatedAt()
				.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

			nextIdAfter = lastRow.getId().toString();
		}

		return CursorResponse.of(
			data,
			nextCursor,
			nextIdAfter,
			hasNext,
			totalCount,
			"UPDATED_AT",
			sortDirection.toUpperCase()
		);
	}

	@Transactional
	public PlaylistAiSession createSession(UUID userId, String title) {
		User user = userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(() -> new MoplException(
				UserErrorCode.USER_NOT_FOUND,
				"존재하지 않는 사용자입니다. userId=" + userId
			));

		return sessionRepository.save(
			new PlaylistAiSession(user, title)
		);
	}

	@Transactional
	public void updateTitle(
		PlaylistAiSession session,
		String title
	) {
		session.updateTitle(title);
		sessionRepository.save(session);
	}

	@Transactional
	public void touchSession(UUID sessionId) {
		sessionRepository.updateUpdatedAt(
			sessionId,
			LocalDateTime.now()
		);
	}
}