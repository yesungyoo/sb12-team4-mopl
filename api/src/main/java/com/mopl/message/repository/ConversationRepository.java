package com.mopl.message.repository;

import com.mopl.core.domain.message.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID>, ConversationRepositoryCustom {

	Optional<Conversation> findByUser1_IdAndUser2_Id(UUID user1Id, UUID user2Id);
}