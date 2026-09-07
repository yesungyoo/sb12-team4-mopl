package com.mopl.core.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

import java.time.LocalDateTime;

@MappedSuperclass
public abstract class BaseEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();

        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }

        onPersist(now);
    }

    protected void onPersist(LocalDateTime now) {

    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
