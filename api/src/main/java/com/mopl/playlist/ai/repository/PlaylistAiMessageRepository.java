package com.mopl.playlist.ai.repository;

import com.mopl.core.domain.playlist.entity.PlaylistAiMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaylistAiMessageRepository
	extends JpaRepository<PlaylistAiMessage, UUID>,
	PlaylistAiMessageRepositoryCustom {

	List<PlaylistAiMessage> findTop10BySession_IdOrderByCreatedAtDescIdDesc(
		UUID sessionId
	);
}