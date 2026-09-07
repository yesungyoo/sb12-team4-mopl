package com.mopl.core.domain.message.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.common.enums.DetectionSource;
import com.mopl.core.common.enums.MessageType;
import com.mopl.core.common.enums.ModerationAction;
import com.mopl.core.common.enums.ModerationCategory;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;
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
    private ModerationCategory moderationCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private ModerationAction moderationAction;

    @Column(name = "original_content", length = 1000)
    private String originalContent;

    protected MessageModerationLog() {
    }

    private MessageModerationLog(
            User sender,
            MessageType messageType,
            Conversation conversation,
            DirectMessage directMessage,
            Content content,
            ContentChatMessage contentChatMessage,
            DetectionSource detectionSource,
            ModerationCategory moderationCategory,
            ModerationAction moderationAction,
            String originalContent
    ) {
        this.sender = Objects.requireNonNull(sender);
        this.messageType = Objects.requireNonNull(messageType);
        this.conversation = conversation;
        this.directMessage = directMessage;
        this.content = content;
        this.contentChatMessage = contentChatMessage;
        this.detectionSource = Objects.requireNonNull(detectionSource);
        this.moderationCategory = Objects.requireNonNull(moderationCategory);
        this.moderationAction = Objects.requireNonNull(moderationAction);
        this.originalContent = originalContent;
    }

    public static MessageModerationLog forDirectMessage(
            User sender,
            Conversation conversation,
            DirectMessage directMessage,
            DetectionSource detectionSource,
            ModerationCategory moderationCategory,
            ModerationAction moderationAction,
            String originalContent
    ) {
        Objects.requireNonNull(conversation);

        return new MessageModerationLog(
                sender,
                MessageType.DM,
                conversation,
                directMessage,
                null,
                null,
                detectionSource,
                moderationCategory,
                moderationAction,
                originalContent
        );
    }

    public static MessageModerationLog forContentChat(
            User sender,
            Content content,
            ContentChatMessage contentChatMessage,
            DetectionSource detectionSource,
            ModerationCategory moderationCategory,
            ModerationAction moderationAction,
            String originalContent
    ) {
        Objects.requireNonNull(content);

        return new MessageModerationLog(
                sender,
                MessageType.CONTENT_CHAT,
                null,
                null,
                content,
                contentChatMessage,
                detectionSource,
                moderationCategory,
                moderationAction,
                originalContent
        );
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

    public ModerationCategory getModerationCategory() {
        return moderationCategory;
    }

    public ModerationAction getModerationAction() {
        return moderationAction;
    }

    public String getOriginalContent() {
        return originalContent;
    }
}