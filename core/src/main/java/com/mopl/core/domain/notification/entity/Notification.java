package com.mopl.core.domain.notification.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.common.enums.NotificationLevel;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

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
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 20)
    private NotificationLevel level;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    protected Notification() {}

    public Notification(User receiver, String title, String content, NotificationLevel level) {
        this.receiver = receiver;
        this.title = title;
        this.content = content;
        this.level = level;
        this.read = false;
    }

    public UUID getId() {
        return id;
    }

    public User getReceiver() {
        return receiver;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public NotificationLevel getLevel() {
        return level;
    }

    public boolean isRead() {
        return read;
    }

    public void markAsRead() {
        this.read = true;
    }
}
