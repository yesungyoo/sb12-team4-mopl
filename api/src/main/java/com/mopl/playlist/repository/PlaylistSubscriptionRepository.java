package com.mopl.playlist.repository;

import com.mopl.core.domain.playlist.entity.PlaylistSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlaylistSubscriptionRepository
	extends JpaRepository<PlaylistSubscription, UUID>, PlaylistSubscriptionRepositoryCustom {

	boolean existsByPlaylistIdAndSubscriberId(UUID playlistId, UUID subscriberId);

	Optional<PlaylistSubscription> findByPlaylistIdAndSubscriberId(UUID playlistId, UUID subscriberId);

	long countByPlaylistId(UUID playlistId);
}