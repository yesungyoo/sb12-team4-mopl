package com.mopl.realtime.watchingsession.service;

import com.mopl.core.common.event.FollowingWatchStartedEvent;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.user.entity.User;
import com.mopl.core.domain.watchingsession.model.WatchingSessionState;
import com.mopl.infrastructure.content.repository.ContentSummaryQueryResult;
import com.mopl.infrastructure.content.repository.ContentSummaryRepository;
import com.mopl.infrastructure.watchingsession.repository.WatchingSessionRedisRepository;
import com.mopl.realtime.common.dto.UserSummary;
import com.mopl.realtime.contentchat.repository.ContentRepository;
import com.mopl.realtime.contentchat.repository.UserRepository;
import com.mopl.realtime.watchingsession.dto.WatchingContentSummary;
import com.mopl.realtime.watchingsession.dto.WatchingSessionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WatchingSessionService {

	private final WatchingSessionRedisRepository watchingSessionRepository;
	private final UserRepository userRepository;
	private final ContentRepository contentRepository;
	private final ContentSummaryRepository contentSummaryRepository;
	private final ApplicationEventPublisher eventPublisher;

	// 사용자당 활성 시청 세션은 하나만 유지한다.
	// 새 시청 세션에 참여하면 기존 세션을 종료하고 새 세션으로 교체한다.
	// TODO Redis의 기존 세션 조회/삭제/신규 저장을 atomic하게 처리하도록 개선한다.
	public WatchingSessionJoinResult join(
		UUID userId,
		UUID contentId,
		String webSocketSessionId,
		String subscriptionId
	) {
		User watcher = userRepository
			.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow();

		Content content = contentRepository
			.findByIdAndDeletedAtIsNull(contentId)
			.orElseThrow();

		List<UUID> followerIds = userRepository.findFollowerIds(userId);

		Optional<WatchingSessionState> previousSession =
			watchingSessionRepository.findByUserId(userId);

		Set<String> leftSubscriptionIds = Set.of();

		if (previousSession.isPresent()) {
			WatchingSessionState previous = previousSession.get();

			boolean sameWatchingSession =
				previous.contentId().equals(contentId)
					&& previous.webSocketSessionId()
					.equals(webSocketSessionId);

			if (sameWatchingSession) {
				watchingSessionRepository.addSubscription(
					webSocketSessionId,
					subscriptionId
				);

				return new WatchingSessionJoinResult(
					previous,
					Optional.empty(),
					Set.of()
				);
			}

			leftSubscriptionIds =
				watchingSessionRepository.findSubscriptionIds(
					previous.webSocketSessionId()
				);

			watchingSessionRepository.delete(previous);
		}

		WatchingSessionState session = new WatchingSessionState(
			UUID.randomUUID(),
			userId,
			contentId,
			webSocketSessionId,
			subscriptionId,
			Instant.now()
		);

		watchingSessionRepository.save(session);

		eventPublisher.publishEvent(
			new FollowingWatchStartedEvent(
				followerIds,
				watcher.getName(),
				content.getTitle()
			)
		);

		return new WatchingSessionJoinResult(
			session,
			previousSession,
			leftSubscriptionIds
		);
	}

	public Optional<WatchingSessionState> leave(String webSocketSessionId) {
		Optional<WatchingSessionState> session =
			watchingSessionRepository.findByWebSocketSessionId(
				webSocketSessionId
			);

		session.ifPresent(watchingSessionRepository::delete);

		return session;
	}

	public Optional<WatchingSessionState> leaveSubscription(
		String webSocketSessionId,
		String subscriptionId
	) {
		Optional<WatchingSessionState> session =
			watchingSessionRepository.findByWebSocketSessionId(
				webSocketSessionId
			);

		if (session.isEmpty()) {
			return Optional.empty();
		}

		boolean removed = watchingSessionRepository.removeSubscription(
			webSocketSessionId,
			subscriptionId
		);

		if (!removed) {
			return Optional.empty();
		}

		if (watchingSessionRepository.hasSubscriptions(
			webSocketSessionId
		)) {
			return Optional.empty();
		}

		WatchingSessionState currentSession = session.get();

		watchingSessionRepository.delete(currentSession);

		return Optional.of(currentSession);
	}

	public WatchingSessionDto toDto(WatchingSessionState session) {
		User watcher = userRepository
			.findByIdAndDeletedAtIsNull(session.watcherId())
			.orElseThrow();

		Content content = contentRepository
			.findByIdAndDeletedAtIsNull(session.contentId())
			.orElseThrow();

		ContentSummaryQueryResult summary =
			contentSummaryRepository.findByContentId(content.getId());

		return new WatchingSessionDto(
			session.id(),
			session.createdAt(),
			UserSummary.from(watcher),
			new WatchingContentSummary(
				content.getId(),
				content.getType(),
				content.getTitle(),
				content.getDescription(),
				content.getThumbnailUrl(),
				summary.tags(),
				summary.averageRating(),
				summary.reviewCount()
			)
		);
	}

	public long count(UUID contentId) {
		return watchingSessionRepository.countByContentId(contentId);
	}
}
