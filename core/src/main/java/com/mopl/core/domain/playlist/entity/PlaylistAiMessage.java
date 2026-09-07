package com.mopl.core.domain.playlist.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.common.enums.PlaylistAiMessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "playlist_ai_messages")
public class PlaylistAiMessage extends BaseEntity {

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
    @JoinColumn(name = "session_id", nullable = false)
    private PlaylistAiSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private PlaylistAiMessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_playlist_id")
    private Playlist resultPlaylist;

    protected PlaylistAiMessage() {
    }

    public PlaylistAiMessage(
            PlaylistAiSession session,
            PlaylistAiMessageRole role,
            String content
    ) {
        this.session = session;
        this.role = role;
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public PlaylistAiSession getSession() {
        return session;
    }

    public PlaylistAiMessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Playlist getResultPlaylist() {
        return resultPlaylist;
    }
}