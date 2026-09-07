package com.mopl.core.domain.message.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;
import jakarta.persistence.criteria.Fetch;

import java.util.UUID;

@Entity
@Table(name = "content_chat_messages")
public class ContentChatMessage extends BaseEntity {

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
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(name = "content", nullable = false, length = 1000)
    private String message;

    protected ContentChatMessage() {}

    public ContentChatMessage(Content content, User sender, String message) {
        this.content = content;
        this.sender = sender;
        this.message = message;
    }

    public UUID getId() {
        return id;
    }

    public Content getContent() {
        return content;
    }

    public User getSender() {
        return sender;
    }

    public String getMessage() {
        return message;
    }
}
