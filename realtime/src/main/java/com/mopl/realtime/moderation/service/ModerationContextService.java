package com.mopl.realtime.moderation.service;

import com.mopl.core.common.enums.DetectionSource;
import com.mopl.core.common.enums.MessageType;
import com.mopl.core.common.enums.ModerationAction;
import com.mopl.realtime.moderation.dto.MessageReviewContext;
import com.mopl.realtime.contentchat.repository.ContentChatMessageRepository;
import com.mopl.realtime.directmessage.repository.DirectMessageRepository;
import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.repository.MessageModerationLogRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ModerationContextService {
    private final MessageModerationLogRepository logs;
    private final ContentChatMessageRepository chats;
    private final DirectMessageRepository messages;
    private final ModerationProperties properties;

    @Transactional(readOnly = true)
    public ReviewContext load(UUID userId, MessageType type, UUID targetId, String currentMessage) {
        var page = PageRequest.of(0, properties.contextLimit());
        var since = LocalDateTime.now().minus(properties.violationWindow());
        var violations = logs.findBySender_IdAndDetectionSourceInAndModerationActionInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            userId, List.of(DetectionSource.RULE, DetectionSource.LLM),
            ModerationLogService.CONFIRMED_ACTIONS, since, page).stream()
            .map(log -> new ContextMessage(userId, log.getCreatedAt(), log.getOriginalContent())).toList();
        var recent = recent(type, targetId);
        return new ReviewContext(userId, type, currentMessage, recent, violations);
    }

    @Transactional(readOnly = true)
    public MessageReviewContext loadMessage(UUID userId, MessageType type, UUID targetId, UUID messageId, String currentMessage) {
        var currentCreatedAt = type == MessageType.DM
            ? messages.findById(messageId).orElseThrow(() -> new IllegalStateException("Message Review 대상 DM을 찾을 수 없습니다.")).getCreatedAt()
            : chats.findById(messageId).orElseThrow(() -> new IllegalStateException("Message Review 대상 채팅을 찾을 수 없습니다.")).getCreatedAt();
        List<MessageCandidate> candidates = type == MessageType.DM
            ? previousMessages(currentCreatedAt, messageId, pageable -> messages
                .findByConversation_IdAndCreatedAtLessThanEqualOrderByCreatedAtDescIdDesc(targetId, currentCreatedAt, pageable)
                .stream().map(message -> new MessageCandidate(message.getId(), message.getSender().getId(),
                    message.getCreatedAt(), message.getContent())).toList())
            : previousMessages(currentCreatedAt, messageId, pageable -> chats
                .findByContent_IdAndCreatedAtLessThanEqualOrderByCreatedAtDescIdDesc(targetId, currentCreatedAt, pageable)
                .stream().map(message -> new MessageCandidate(message.getId(), message.getSender().getId(),
                    message.getCreatedAt(), message.getMessage())).toList());
        var recent = candidates.stream()
            .map(message -> new MessageReviewContext.ConversationMessage(message.senderId().equals(userId), message.text()))
            .toList();
        return new MessageReviewContext(currentMessage, recent);
    }

    private List<MessageCandidate> previousMessages(LocalDateTime currentCreatedAt, UUID currentMessageId,
        Function<org.springframework.data.domain.Pageable, List<MessageCandidate>> fetch) {
        int limit = properties.contextLimit();
        var result = new ArrayList<MessageCandidate>(limit);
        int pageNumber = 0;
        List<MessageCandidate> page;
        do {
            page = fetch.apply(PageRequest.of(pageNumber++, limit));
            for (var candidate : page) {
                int timeOrder = candidate.createdAt().compareTo(currentCreatedAt);
                boolean beforeCurrent = timeOrder < 0 || (timeOrder == 0
                    && candidate.id().toString().compareTo(currentMessageId.toString()) < 0);
                if (beforeCurrent) {
                    result.add(candidate);
                    if (result.size() == limit) return List.copyOf(result);
                }
            }
        } while (page.size() == limit);
        return List.copyOf(result);
    }

    private List<ContextMessage> recent(MessageType type, UUID targetId) {
        var page = PageRequest.of(0, properties.contextLimit());
        return type == MessageType.DM
            ? messages.findByConversation_IdOrderByCreatedAtDesc(targetId, page).stream()
                .map(message -> new ContextMessage(message.getSender().getId(), message.getCreatedAt(), message.getContent())).toList()
            : chats.findByContent_IdOrderByCreatedAtDesc(targetId, page).stream()
                .map(message -> new ContextMessage(message.getSender().getId(), message.getCreatedAt(), message.getMessage())).toList();
    }

    public record ContextMessage(UUID senderId, LocalDateTime createdAt, String text) {}
    private record MessageCandidate(UUID id, UUID senderId, LocalDateTime createdAt, String text) {}
    public record ReviewContext(UUID reviewedUserId, MessageType messageType, String currentMessage,
                                List<ContextMessage> recentConversation, List<ContextMessage> violations) {}
}
