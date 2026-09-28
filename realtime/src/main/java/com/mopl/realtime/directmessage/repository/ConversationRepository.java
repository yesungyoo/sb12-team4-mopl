package com.mopl.realtime.directmessage.repository;

import com.mopl.core.domain.message.entity.Conversation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

	@Query("""
            select count(c) > 0
            from Conversation c
            where c.id = :conversationId
              and (c.user1.id = :userId or c.user2.id = :userId)
            """)
	boolean existsByIdAndParticipantId(
		@Param("conversationId") UUID conversationId,
		@Param("userId") UUID userId
	);

	@EntityGraph(attributePaths = {"user1", "user2"})
	@Query("""
            select c
            from Conversation c
            where c.id = :conversationId
            """)
	Optional<Conversation> findWithParticipantsById(
		@Param("conversationId") UUID conversationId
	);
}