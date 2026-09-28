package com.mopl.realtime.directmessage.repository;

import com.mopl.core.domain.message.entity.DirectMessage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, UUID> {
}