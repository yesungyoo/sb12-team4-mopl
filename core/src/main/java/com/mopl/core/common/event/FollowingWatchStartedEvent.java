package com.mopl.core.common.event;

import java.util.List;
import java.util.UUID;

public record FollowingWatchStartedEvent(
    List<UUID> followerIds,
    String watcherName,
    String contentTitle
) {
}