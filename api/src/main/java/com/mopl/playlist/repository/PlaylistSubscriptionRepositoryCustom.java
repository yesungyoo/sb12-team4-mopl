package com.mopl.playlist.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface PlaylistSubscriptionRepositoryCustom {
	Map<UUID, Long> countByPlaylistIdIn(List<UUID> playlistIds);
	Set<UUID> findSubscribedPlaylistIds(List<UUID> playlistIds, UUID subscriberId);
}