package com.mopl.infrastructure.watchingsession.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.core.domain.watchingsession.model.WatchingSessionState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class WatchingSessionRedisRepository {

	private final RedisTemplate<String, String> redisTemplate;
	private final ObjectMapper objectMapper;

	public WatchingSessionRedisRepository(
		@Qualifier("redisTemplate") RedisTemplate<String, String> redisTemplate,
		ObjectMapper objectMapper
	) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	public void save(WatchingSessionState session) {
		String value = serialize(session);

		// 사용자 → 현재 시청 세션
		redisTemplate.opsForValue()
			.set(userKey(session.watcherId()), value);

		// WebSocket 연결 → 현재 시청 세션
		redisTemplate.opsForValue()
			.set(webSocketKey(session.webSocketSessionId()), value);

		// 콘텐츠 → 현재 시청 중인 사용자
		redisTemplate.opsForZSet()
			.add(
				contentKey(session.contentId()),
				session.watcherId().toString(),
				session.createdAt().toEpochMilli()
			);

		addSubscription(
			session.webSocketSessionId(),
			session.subscriptionId()
		);
	}

	public Optional<WatchingSessionState> findByUserId(UUID userId) {
		String value = redisTemplate.opsForValue().get(userKey(userId));

		if (value == null) {
			return Optional.empty();
		}

		return Optional.of(deserialize(value));
	}

	public Optional<WatchingSessionState> findByWebSocketSessionId(String sessionId) {
		String value = redisTemplate.opsForValue().get(webSocketKey(sessionId));

		if (value == null) {
			return Optional.empty();
		}

		return Optional.of(deserialize(value));
	}

	public long countByContentId(UUID contentId) {
		Long count = redisTemplate.opsForZSet().size(contentKey(contentId));
		return count == null ? 0 : count;
	}

	public List<WatchingSessionState> findAllByContentId(UUID contentId) {
		Set<String> watcherIds = redisTemplate.opsForZSet()
			.range(contentKey(contentId), 0, -1);

		if (watcherIds == null || watcherIds.isEmpty()) {
			return List.of();
		}

		return watcherIds.stream()
			.map(UUID::fromString)
			.map(this::findByUserId)
			.flatMap(Optional::stream)
			.filter(session -> session.contentId().equals(contentId))
			.toList();
	}

	public void addSubscription(
		String webSocketSessionId,
		String subscriptionId
	) {
		redisTemplate.opsForSet()
			.add(
				subscriptionKey(webSocketSessionId),
				subscriptionId
			);
	}

	public boolean removeSubscription(
		String webSocketSessionId,
		String subscriptionId
	) {
		Long removed = redisTemplate.opsForSet()
			.remove(
				subscriptionKey(webSocketSessionId),
				subscriptionId
			);

		return removed != null && removed > 0;
	}

	public boolean hasSubscriptions(String webSocketSessionId) {
		Long size = redisTemplate.opsForSet()
			.size(subscriptionKey(webSocketSessionId));

		return size != null && size > 0;
	}

	public Set<String> findSubscriptionIds(String webSocketSessionId) {
		Set<String> subscriptionIds = redisTemplate.opsForSet()
			.members(subscriptionKey(webSocketSessionId));

		if (subscriptionIds == null || subscriptionIds.isEmpty()) {
			return Set.of();
		}

		return Set.copyOf(subscriptionIds);
	}

	public void delete(WatchingSessionState session) {
		redisTemplate.delete(userKey(session.watcherId()));
		redisTemplate.delete(webSocketKey(session.webSocketSessionId()));
		redisTemplate.delete(subscriptionKey(session.webSocketSessionId()));

		redisTemplate.opsForZSet()
			.remove(
				contentKey(session.contentId()),
				session.watcherId().toString()
			);
	}

	private String userKey(UUID userId) {
		return "watch:user:" + userId;
	}

	private String contentKey(UUID contentId) {
		return "watch:content:" + contentId;
	}

	private String webSocketKey(String sessionId) {
		return "watch:ws:" + sessionId;
	}

	private String subscriptionKey(String sessionId) {
		return "watch:ws-subscriptions:" + sessionId;
	}

	private String serialize(WatchingSessionState session) {
		try {
			return objectMapper.writeValueAsString(session);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(
				"Failed to serialize watching session",
				e
			);
		}
	}

	private WatchingSessionState deserialize(String value) {
		try {
			return objectMapper.readValue(
				value,
				WatchingSessionState.class
			);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(
				"Failed to deserialize watching session",
				e
			);
		}
	}
}
