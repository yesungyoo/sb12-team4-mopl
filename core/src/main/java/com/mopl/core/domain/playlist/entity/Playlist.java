package com.mopl.core.domain.playlist.entity;

import com.mopl.core.common.entity.BaseUpdatableEntity;
import com.mopl.core.domain.user.entity.User;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "playlists")
public class Playlist extends BaseUpdatableEntity {

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
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    protected Playlist() {}

    public Playlist(
            User owner,
            String title,
            String description
    ) {
        this.owner = owner;
        this.title = title;
        this.description = description;
    }

	public void update(String title, String description) {
		if (title != null) {
			this.title = title;
		}
		if (description != null) {
			this.description = description;
		}
	}

    public UUID getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }


}
