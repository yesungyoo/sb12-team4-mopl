package com.mopl.core.domain.playlist.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.domain.content.entity.Content;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "playlist_contents")
public class PlaylistContent extends BaseEntity {

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
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    protected PlaylistContent() {}

    public PlaylistContent(Playlist playlist, Content content) {
        this.playlist = playlist;
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public Playlist getPlaylist() {
        return playlist;
    }

    public Content getContent() {
        return content;
    }
}
