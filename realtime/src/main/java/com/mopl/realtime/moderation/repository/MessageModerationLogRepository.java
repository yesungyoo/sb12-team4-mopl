package com.mopl.realtime.moderation.repository;

import com.mopl.core.common.enums.DetectionSource;
import com.mopl.core.common.enums.ModerationAction;
import com.mopl.core.domain.message.entity.MessageModerationLog;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageModerationLogRepository extends JpaRepository<MessageModerationLog, UUID> {
    long countBySender_IdAndDetectionSourceInAndModerationActionInAndCreatedAtGreaterThanEqual(
        UUID senderId, Collection<DetectionSource> sources, Collection<ModerationAction> actions, LocalDateTime since);
    List<MessageModerationLog> findBySender_IdAndDetectionSourceInAndModerationActionInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        UUID senderId, Collection<DetectionSource> sources, Collection<ModerationAction> actions,
        LocalDateTime since, Pageable pageable);
    boolean existsByDirectMessage_IdAndDetectionSourceAndModerationAction(UUID messageId, DetectionSource source, ModerationAction action);
    boolean existsByContentChatMessage_IdAndDetectionSourceAndModerationAction(UUID messageId, DetectionSource source, ModerationAction action);
}

