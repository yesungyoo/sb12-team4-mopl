package com.mopl.watchingsession.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.user.entity.User;
import com.mopl.core.domain.watchingsession.model.WatchingSessionState;
import com.mopl.infrastructure.content.repository.ContentSummaryQueryResult;
import com.mopl.infrastructure.content.repository.ContentSummaryRepository;
import com.mopl.infrastructure.watchingsession.repository.WatchingSessionRedisRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.WatchingContentSummary;
import com.mopl.watchingsession.dto.WatchingSessionResponse;
import com.mopl.watchingsession.dto.WatchingUserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchingSessionService {

	private static final int MAX_LIMIT = 100;

	private final WatchingSessionRedisRepository watchingSessionRepository;
	private final UserRepository userRepository;
	private final ContentRepository contentRepository;
	private final ContentSummaryRepository contentSummaryRepository;

	public WatchingSessionResponse getWatchingSession(UUID watcherId) {
		WatchingSessionState session = watchingSessionRepository
			.findByUserId(watcherId)
			.orElse(null);

		if (session == null) {
			return null;
		}

		User watcher = userRepository
			.findByIdAndDeletedAtIsNull(session.watcherId())
			.orElseThrow(() -> new MoplException(
				UserErrorCode.USER_NOT_FOUND,
				"존재하지 않는 사용자입니다. userId=" + session.watcherId()
			));

		Content content = contentRepository
			.findByIdAndDeletedAtIsNull(session.contentId())
			.orElseThrow(ContentNotFoundException::new);

		WatchingContentSummary contentSummary = createContentSummary(content);

		return new WatchingSessionResponse(
			session.id(),
			session.createdAt(),
			WatchingUserSummary.from(watcher),
			contentSummary
		);
	}

	public CursorResponse<WatchingSessionResponse> getWatchingSessions(
		UUID contentId,
		String watcherNameLike,
		String cursor,
		UUID idAfter,
		int limit,
		String sortBy,
		String sortDirection
	) {
		if (limit <= 0) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		int safeLimit = Math.min(limit, MAX_LIMIT);

		if ((cursor == null) != (idAfter == null)) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		if (!"createdAt".equals(sortBy)) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		boolean ascending;
		if ("ASCENDING".equalsIgnoreCase(sortDirection)) {
			ascending = true;
		} else if ("DESCENDING".equalsIgnoreCase(sortDirection)) {
			ascending = false;
		} else {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		Content content = contentRepository
			.findByIdAndDeletedAtIsNull(contentId)
			.orElseThrow(ContentNotFoundException::new);

		WatchingContentSummary contentSummary = createContentSummary(content);

		List<WatchingSessionResponse> sessions = watchingSessionRepository
			.findAllByContentId(contentId)
			.stream()
			.map(session -> {
				User watcher = userRepository
					.findByIdAndDeletedAtIsNull(session.watcherId())
					.orElse(null);

				if (watcher == null) {
					return null;
				}

				return new WatchingSessionResponse(
					session.id(),
					session.createdAt(),
					WatchingUserSummary.from(watcher),
					contentSummary
				);
			})
			.filter(java.util.Objects::nonNull)
			.filter(response ->
				watcherNameLike == null
					|| watcherNameLike.isBlank()
					|| response.watcher().name()
					.toLowerCase(Locale.ROOT)
					.contains(watcherNameLike.toLowerCase(Locale.ROOT))
			)
			.sorted((a, b) -> {
				int compared = a.createdAt().compareTo(b.createdAt());

				if (compared == 0) {
					compared = a.id().compareTo(b.id());
				}

				return ascending ? compared : -compared;
			})
			.toList();

		long totalCount = sessions.size();

		if (cursor != null) {
			Instant cursorCreatedAt;
			try {
				cursorCreatedAt = Instant.parse(cursor);
			} catch (Exception e) {
				throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
			}

			sessions = sessions.stream()
				.filter(response -> {
					int compared = response.createdAt().compareTo(cursorCreatedAt);

					if (compared == 0) {
						compared = response.id().compareTo(idAfter);
					}

					return ascending ? compared > 0 : compared < 0;
				})
				.toList();
		}

		boolean hasNext = sessions.size() > safeLimit;

		List<WatchingSessionResponse> data = hasNext
			? sessions.subList(0, safeLimit)
			: sessions;

		String nextCursor = null;
		UUID nextIdAfter = null;

		if (hasNext) {
			WatchingSessionResponse last = data.getLast();
			nextCursor = last.createdAt().toString();
			nextIdAfter = last.id();
		}

		return CursorResponse.of(
			data,
			nextCursor,
			nextIdAfter != null ? nextIdAfter.toString() : null,
			hasNext,
			totalCount,
			sortBy,
			sortDirection.toUpperCase(Locale.ROOT)
		);
	}

	private WatchingContentSummary createContentSummary(Content content) {
		ContentSummaryQueryResult summary =
			contentSummaryRepository.findByContentId(content.getId());

		return new WatchingContentSummary(
			content.getId(),
			content.getType(),
			content.getTitle(),
			content.getDescription(),
			content.getThumbnailUrl(),
			summary.tags(),
			summary.averageRating(),
			summary.reviewCount()
		);
	}
}