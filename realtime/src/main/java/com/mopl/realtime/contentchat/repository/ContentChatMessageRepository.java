package com.mopl.realtime.contentchat.repository;

import com.mopl.core.domain.message.entity.ContentChatMessage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentChatMessageRepository
	extends JpaRepository<ContentChatMessage, UUID> {
}