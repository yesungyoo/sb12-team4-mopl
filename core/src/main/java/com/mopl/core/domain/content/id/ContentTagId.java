package com.mopl.core.domain.content.id;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ContentTagId implements Serializable {

    @Column(
            name = "content_id",
            nullable = false,
            columnDefinition = "CHAR(36)"
    )
    private UUID contentId;

    @Column(name = "tag", nullable = false, length = 30)
    private String tag;

    @Column(name = "value", nullable = false, length = 100)
    private String value;

    protected ContentTagId() {
    }

    public ContentTagId(UUID contentId, String tag, String value) {
        this.contentId = contentId;
        this.tag = tag;
        this.value = value;
    }

    public UUID getContentId() {
        return contentId;
    }

    public String getTag() {
        return tag;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof ContentTagId that)) {
            return false;
        }

        return Objects.equals(contentId, that.contentId)
                && Objects.equals(tag, that.tag)
                && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contentId, tag, value);
    }
}