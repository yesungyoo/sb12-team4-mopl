package com.mopl.core.domain.notification.entity;

import com.mopl.core.common.entity.BaseUpdatableEntity;
import com.mopl.core.common.enums.NotificationType;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreference extends BaseUpdatableEntity {

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
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NotificationType type;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected NotificationPreference() {}

    public NotificationPreference(User user, NotificationType type) {
        this.user = user;
        this.type = type;
        this.enabled = true;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public NotificationType getType() {
        return type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void turnOn() {
        this.enabled = true;
    }

    public void turnOff() {
        this.enabled = false;
    }
}
