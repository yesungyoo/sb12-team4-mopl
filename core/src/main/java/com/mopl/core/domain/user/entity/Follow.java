package com.mopl.core.domain.user.entity;

import com.mopl.core.common.entity.BaseEntity;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "follows")
public class Follow extends BaseEntity {

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
    @JoinColumn(name = "follower_id", nullable = false)
    private User follower;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "followee_id", nullable = false)
    private User followee;

    protected Follow() {}

    public Follow(
            User follower,
            User followee
    ) {
        this.follower = follower;
        this.followee = followee;
    }

    public UUID getId() {
        return id;
    }

    public User getFollower() {
        return follower;
    }

    public User getFollowee() {
        return followee;
    }
}