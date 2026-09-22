package com.mopl.core.domain.review.entity;

import com.mopl.core.common.entity.BaseUpdatableEntity;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "reviews")
public class Review extends BaseUpdatableEntity {

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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(name = "rating", nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(name = "text", nullable = false, length = 1000)
    private String text;

    protected Review() {}

    public Review(
            User user,
            Content content,
            BigDecimal rating,
            String text
    ) {
        this.user = user;
        this.content = content;
        this.rating = rating;
        this.text = text;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Content getContent() {
        return content;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public String getText() {
        return text;
    }

    public void update(BigDecimal rating, String text) {
        if (rating != null) {
            this.rating = rating;
        }

        if (text != null) {
            this.text = text;
        }
    }

    public boolean isWrittenBy(UUID userId) {
        return user.getId().equals(userId);
    }
}
