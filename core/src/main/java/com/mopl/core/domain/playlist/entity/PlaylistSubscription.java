package com.mopl.core.domain.playlist.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "playlist_subscriptions")
public class PlaylistSubscription extends BaseEntity {

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
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscriber_id", nullable = false)
    private User subscriber;

    protected PlaylistSubscription() {}

    public PlaylistSubscription(Playlist playlist, User subscriber) {
        this.playlist = playlist;
        this.subscriber = subscriber;
    }

    public UUID getId() {
        return id;
    }

    public Playlist getPlaylist() {
        return playlist;
    }

    public User getSubscriber() {
        return subscriber;
    }
}
