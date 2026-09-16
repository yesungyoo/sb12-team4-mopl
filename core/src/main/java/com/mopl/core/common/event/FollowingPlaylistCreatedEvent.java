package com.mopl.core.common.event;

import java.util.List;
import java.util.UUID;

public record FollowingPlaylistCreatedEvent(
    List<UUID> followerIds,
    String creatorName,
    String playlistTitle
) {
}