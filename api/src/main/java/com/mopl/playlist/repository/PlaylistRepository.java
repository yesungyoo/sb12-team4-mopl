package com.mopl.playlist.repository;

import com.mopl.core.domain.playlist.entity.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlaylistRepository extends JpaRepository<Playlist, UUID>, PlaylistRepositoryCustom {
}