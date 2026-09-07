package com.mopl.core.domain.message.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.common.enums.DetectionSource;
import com.mopl.core.common.enums.MessageType;
import com.mopl.core.common.enums.ModerationAction;
import com.mopl.core.common.enums.ModerationCategory;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "message_moderation_logs")
public class MessageModerationLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(36)"
    )
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 20)
    private MessageType messageType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "direct_message_id")
    private DirectMessage directMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_id")
    private Content content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_chat_message_id")
    private ContentChatMessage contentChatMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "detection_source", nullable = false, length = 20)
    private DetectionSource detectionSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private ModerationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private ModerationAction action;

    @Column(name = "original_content", length = 1000)
    private String originalContent;

    protected MessageModerationLog() {
    }

    public UUID getId() {
        return id;
    }

    public User getSender() {
        return sender;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public DirectMessage getDirectMessage() {
        return directMessage;
    }

    public Content getContent() {
        return content;
    }

    public ContentChatMessage getContentChatMessage() {
        return contentChatMessage;
    }

    public DetectionSource getDetectionSource() {
        return detectionSource;
    }

    public ModerationCategory getCategory() {
        return category;
    }

    public ModerationAction getAction() {
        return action;
    }

    public String getOriginalContent() {
        return originalContent;
    }
}
