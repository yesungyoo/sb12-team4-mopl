package com.mopl.realtime.moderation.service;

import com.mopl.core.common.enums.DetectionSource;
import com.mopl.core.common.enums.MessageType;
import com.mopl.core.common.enums.ModerationAction;
import com.mopl.core.common.enums.ModerationCategory;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.message.entity.ContentChatMessage;
import com.mopl.core.domain.message.entity.MessageModerationLog;
import com.mopl.core.domain.user.entity.User;
import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.repository.MessageModerationLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ModerationLogService {
    static final List<ModerationAction> CONFIRMED_ACTIONS =
        List.of(ModerationAction.MASK, ModerationAction.BLOCK, ModerationAction.VIOLATION);
    private final MessageModerationLogRepository repository;
    private final EntityManager entityManager;
    private final ModerationProperties properties;

    // 메시지 차단으로 외부 전송 트랜잭션이 롤백되어도 위반 증거는 보존한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID senderId, MessageType type, UUID targetId, String text,
        DetectionSource source, ModerationCategory category, ModerationAction action) {
        if (action == ModerationAction.ALLOW) throw new IllegalArgumentException("Not a violation");
        User sender = entityManager.getReference(User.class, senderId);
        MessageModerationLog log = type == MessageType.DM
            ? MessageModerationLog.forDirectMessage(sender, entityManager.getReference(Conversation.class, targetId),
                null, source, category, action, text)
            : MessageModerationLog.forContentChat(sender, entityManager.getReference(Content.class, targetId),
                null, source, category, action, text);
        repository.saveAndFlush(log);
    }

    // 같은 메시지의 Review 작업이 중복 제출되어도 기존 메시지 행 잠금으로 기록을 직렬화한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordReviewedViolation(UUID messageId, MessageType type, String original) {
        MessageModerationLog log;
        if (type == MessageType.DM) {
            DirectMessage message = entityManager.find(DirectMessage.class, messageId, LockModeType.PESSIMISTIC_WRITE);
            if (message == null) throw new IllegalArgumentException("Message not found");
            if (repository.existsByDirectMessage_IdAndDetectionSourceAndModerationAction(
                messageId, DetectionSource.LLM, ModerationAction.VIOLATION)) return false;
            log = MessageModerationLog.forDirectMessage(message.getSender(), message.getConversation(), message,
                DetectionSource.LLM, ModerationCategory.HARMFUL, ModerationAction.VIOLATION, original);
        } else {
            ContentChatMessage message = entityManager.find(ContentChatMessage.class, messageId, LockModeType.PESSIMISTIC_WRITE);
            if (message == null) throw new IllegalArgumentException("Message not found");
            if (repository.existsByContentChatMessage_IdAndDetectionSourceAndModerationAction(
                messageId, DetectionSource.LLM, ModerationAction.VIOLATION)) return false;
            log = MessageModerationLog.forContentChat(message.getSender(), message.getContent(), message,
                DetectionSource.LLM, ModerationCategory.HARMFUL, ModerationAction.VIOLATION, original);
        }
        repository.saveAndFlush(log);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long recentCount(UUID senderId) {
        return repository.countBySender_IdAndDetectionSourceInAndModerationActionInAndCreatedAtGreaterThanEqual(
            senderId, List.of(DetectionSource.RULE, DetectionSource.LLM), CONFIRMED_ACTIONS, LocalDateTime.now().minus(properties.violationWindow()));
    }
}
