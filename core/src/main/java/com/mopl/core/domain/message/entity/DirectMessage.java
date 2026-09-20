package com.mopl.core.domain.message.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "direct_messages")
public class DirectMessage extends BaseEntity {

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
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    protected DirectMessage() {}

    public DirectMessage(Conversation conversation, User sender, User receiver, String content) {
        this.conversation = conversation;
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
    }

	public void markAsRead() {
		if (this.readAt == null) {
			this.readAt = LocalDateTime.now();
		}
	}

    public UUID getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public User getSender() {
        return sender;
    }

    public User getReceiver() {
        return receiver;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }
}
