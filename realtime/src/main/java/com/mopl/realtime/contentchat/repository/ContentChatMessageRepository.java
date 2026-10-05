package com.mopl.realtime.contentchat.repository;

import com.mopl.core.domain.message.entity.ContentChatMessage;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentChatMessageRepository
	extends JpaRepository<ContentChatMessage, UUID> {
    List<ContentChatMessage> findByContent_IdOrderByCreatedAtDesc(UUID contentId, Pageable pageable);
    List<ContentChatMessage> findByContent_IdAndCreatedAtLessThanEqualOrderByCreatedAtDescIdDesc(
        UUID contentId, java.time.LocalDateTime createdAt, Pageable pageable);
}
