package com.mopl.core.domain.content.entity;

import com.mopl.core.domain.content.id.ContentTagId;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "content_tags")
public class ContentTag {

    @EmbeddedId
    private ContentTagId id;

    @MapsId("contentId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    protected ContentTag() {
    }

    public ContentTag(Content content, String tag, String value) {
        this.content = content;
        this.id = new ContentTagId(
                content.getId(),
                tag,
                value
        );
    }

    public ContentTagId getId() {
        return id;
    }

    public Content getContent() {
        return content;
    }

    public String getTag() {
        return id.getTag();
    }

    public String getValue() {
        return id.getValue();
    }
}