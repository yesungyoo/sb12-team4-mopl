package com.mopl.playlist.ai.repository;

import com.mopl.core.domain.playlist.entity.PlaylistAiSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PlaylistAiSessionRepository
	extends JpaRepository<PlaylistAiSession, UUID>,
	PlaylistAiSessionRepositoryCustom {

	Optional<PlaylistAiSession> findByIdAndUser_Id(
		UUID sessionId,
		UUID userId
	);

	@Modifying
	@Query("""
        update PlaylistAiSession session
        set session.updatedAt = :updatedAt
        where session.id = :sessionId
        """)
	void updateUpdatedAt(
		@Param("sessionId") UUID sessionId,
		@Param("updatedAt") LocalDateTime updatedAt
	);
}