package com.mopl.playlist.ai.service;

import lombok.RequiredArgsConstructor;
import com.mopl.common.exception.playlist.PlaylistAiCandidateStoreUnavailableException;
import com.mopl.playlist.ai.config.AiPlaylistProperties;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AiPlaylistCandidateStore {

	private static final String KEY_PREFIX =
		"ai:playlist:candidates:";

	private static final DefaultRedisScript<Long> SAVE_CANDIDATES_SCRIPT =
		new DefaultRedisScript<>("""
                for i = 2, #ARGV do
                    redis.call('SADD', KEYS[1], ARGV[i])
                end
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
                return 1
                """, Long.class);

	private final StringRedisTemplate redisTemplate;
	private final AiPlaylistProperties properties;

	public void saveCandidates(
		UUID userId,
		String sessionId,
		Set<UUID> contentIds
	) {
		if (contentIds.isEmpty()) {
			return;
		}

		String key = buildKey(userId, sessionId);

		List<String> args = new ArrayList<>();
		args.add(String.valueOf(properties.candidateTtl().toMillis()));
		args.addAll(
			contentIds.stream()
				.map(UUID::toString)
				.toList()
		);

		try {
			redisTemplate.execute(
				SAVE_CANDIDATES_SCRIPT,
				List.of(key),
				args.toArray()
			);
		} catch (DataAccessException e) {
			throw new PlaylistAiCandidateStoreUnavailableException();
		}
	}

	public boolean containsAll(
		UUID userId,
		String sessionId,
		Set<UUID> contentIds
	) {
		if (contentIds.isEmpty()) {
			return false;
		}

		String key = buildKey(userId, sessionId);

		Object[] values = contentIds.stream()
			.map(UUID::toString)
			.toArray();

		try {
			Map<Object, Boolean> membership =
				redisTemplate.opsForSet().isMember(key, values);

			return membership != null
				&& membership.size() == contentIds.size()
				&& membership.values()
				.stream()
				.allMatch(Boolean.TRUE::equals);
		} catch (DataAccessException e) {
			throw new PlaylistAiCandidateStoreUnavailableException();
		}
	}

	private String buildKey(
		UUID userId,
		String sessionId
	) {
		return KEY_PREFIX
			+ userId
			+ ":"
			+ sessionId;
	}
}