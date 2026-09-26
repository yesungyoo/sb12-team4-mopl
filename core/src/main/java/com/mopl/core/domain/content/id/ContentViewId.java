package com.mopl.core.domain.content.id;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ContentViewId implements Serializable {

    @Column(name = "user_id", nullable = false, columnDefinition = "CHAR(36)")
    private UUID userId;

    @Column(name = "content_id", nullable = false, columnDefinition = "CHAR(36)")
    private UUID contentId;

    protected ContentViewId() {}

    public ContentViewId(UUID userId, UUID contentId) {
        this.userId = userId;
        this.contentId = contentId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getContentId() {
        return contentId;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof ContentViewId that)) {
            return false;
        }

        return Objects.equals(userId, that.userId) && Objects.equals(contentId, that.contentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, contentId);
    }
}
