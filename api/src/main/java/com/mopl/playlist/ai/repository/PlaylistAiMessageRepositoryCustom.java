package com.mopl.playlist.ai.repository;

import com.mopl.core.domain.playlist.entity.PlaylistAiMessage;

import java.util.List;
import java.util.UUID;

public interface PlaylistAiMessageRepositoryCustom {

	List<PlaylistAiMessage> findBySessionCursor(
		UUID sessionId,
		String cursor,
		UUID idAfter,
		int limit,
		boolean ascending
	);

	long countBySession(UUID sessionId);
}