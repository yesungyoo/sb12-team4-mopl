package com.mopl.playlist.ai.repository;

import com.mopl.core.domain.playlist.entity.PlaylistAiSession;

import java.util.List;
import java.util.UUID;

public interface PlaylistAiSessionRepositoryCustom {

	List<PlaylistAiSession> findByUserCursor(
		UUID userId,
		String cursor,
		UUID idAfter,
		int limit,
		boolean ascending
	);

	long countByUser(UUID userId);
}