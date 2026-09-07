package com.mopl.core.domain.message.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation extends BaseEntity {

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
    @JoinColumn(name = "user1_id", nullable = false)
    private User user1;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user2_id", nullable = false)
    private User user2;

    protected Conversation() {}

    public Conversation(User user1, User user2) {
        this.user1 = user1;
        this.user2 = user2;
    }

    public UUID getId() {
        return id;
    }

    public User getUser1() {
        return user1;
    }

    public User getUser2() {
        return user2;
    }
}
