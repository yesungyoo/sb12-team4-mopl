package com.mopl.core.domain.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sports_event_details")
public class SportsEventDetail {

    @Id
    @Column(
            name = "content_id",
            nullable = false,
            columnDefinition = "CHAR(36)"
    )
    private UUID contentId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(name = "sport", nullable = false, length = 50)
    private String sport;

    @Column(name = "league_name", length = 255)
    private String leagueName;

    @Column(name = "home_team_name", length = 255)
    private String homeTeamName;

    @Column(name = "away_team_name", length = 255)
    private String awayTeamName;

    @Column(name = "event_at")
    private LocalDateTime eventAt;

    @Column(name = "status", length = 50)
    private String status;

    protected SportsEventDetail() {
    }

    public SportsEventDetail(
            Content content,
            String sport,
            String leagueName,
            String homeTeamName,
            String awayTeamName,
            LocalDateTime eventAt,
            String status
    ) {
        this.content = content;
        this.sport = sport;
        this.leagueName = leagueName;
        this.homeTeamName = homeTeamName;
        this.awayTeamName = awayTeamName;
        this.eventAt = eventAt;
        this.status = status;
    }

    public UUID getContentId() {
        return contentId;
    }

    public Content getContent() {
        return content;
    }

    public String getSport() {
        return sport;
    }

    public String getLeagueName() {
        return leagueName;
    }

    public String getHomeTeamName() {
        return homeTeamName;
    }

    public String getAwayTeamName() {
        return awayTeamName;
    }

    public LocalDateTime getEventAt() {
        return eventAt;
    }

    public String getStatus() {
        return status;
    }
}