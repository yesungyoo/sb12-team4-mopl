package com.mopl.message.repository;

import com.mopl.core.domain.message.entity.DirectMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, UUID>, DirectMessageRepositoryCustom {

	Optional<DirectMessage> findTopByConversation_IdOrderByCreatedAtDesc(UUID conversationId);

	boolean existsByConversation_IdAndReceiver_IdAndReadAtIsNull(UUID conversationId, UUID receiverId);
}