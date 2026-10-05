package com.mopl.realtime.directmessage.repository;

import com.mopl.core.domain.message.entity.DirectMessage;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, UUID> {
    List<DirectMessage> findByConversation_IdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);
    List<DirectMessage> findByConversation_IdAndCreatedAtLessThanEqualOrderByCreatedAtDescIdDesc(
        UUID conversationId, java.time.LocalDateTime createdAt, Pageable pageable);
}
