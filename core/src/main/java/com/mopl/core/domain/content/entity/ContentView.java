package com.mopl.core.domain.content.entity;

import com.mopl.core.domain.content.id.ContentViewId;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "content_views")
public class ContentView {

    @EmbeddedId
    private ContentViewId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @MapsId("contentId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(name = "view_count", nullable = false)
    private Long viewCount;

    @Column(name = "first_viewed_at", nullable = false)
    private LocalDateTime firstViewedAt;

    @Column(name = "last_viewed_at", nullable = false)
    private LocalDateTime lastViewedAt;

    protected ContentView() {}

    public ContentView(User user, Content content, LocalDateTime viewedAt) {
        this.id = new ContentViewId(user.getId(), content.getId());
        this.user = user;
        this.content = content;
        this.viewCount = 1L;
        this.firstViewedAt = viewedAt;
        this.lastViewedAt = viewedAt;
    }

    public ContentViewId getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Content getContent() {
        return content;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public LocalDateTime getFirstViewedAt() {
        return firstViewedAt;
    }

    public LocalDateTime getLastViewedAt() {
        return lastViewedAt;
    }
}
