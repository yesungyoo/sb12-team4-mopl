package com.mopl.core.domain.content.entity;

import com.mopl.core.common.entity.BaseUpdatableEntity;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "contents")
public class Content extends BaseUpdatableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(36)"
    )
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ContentType type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "thubnail_url", length = 1000)
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "external_source", nullable = false, length = 30)
    private ExternalSource externalSource;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "external_popularity", precision = 15, scale = 4)
    private BigDecimal externalPopularity;

    @Column(name = "external_rating", precision = 4, scale = 2)
    private BigDecimal externalRating;

    @Column(name = "external_vote_count")
    private Long externalVoteCount;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected Content() {}

    public Content(
            ContentType type,
            String title,
            String description,
            String thumbnailUrl,
            ExternalSource externalSource,
            String externalId,
            LocalDate releaseDate,
            BigDecimal externalPopularity,
            BigDecimal externalRating,
            Long externalVoteCount
    ) {
        this.type = type;
        this.title = title;
        this.description = description;
        this.thumbnailUrl = thumbnailUrl;
        this.externalSource = externalSource;
        this.externalId = externalId;
        this.releaseDate = releaseDate;
        this.externalPopularity = externalPopularity;
        this.externalRating = externalRating;
        this.externalVoteCount = externalVoteCount;
    }

    public UUID getId() {
        return id;
    }

    public ContentType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public ExternalSource getExternalSource() {
        return externalSource;
    }

    public String getExternalId() {
        return externalId;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public BigDecimal getExternalPopularity() {
        return externalPopularity;
    }

    public BigDecimal getExternalRating() {
        return externalRating;
    }

    public Long getExternalVoteCount() {
        return externalVoteCount;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }
}
