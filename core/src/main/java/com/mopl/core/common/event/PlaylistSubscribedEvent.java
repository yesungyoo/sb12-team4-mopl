package com.mopl.core.common.event;

import java.util.UUID;

public record PlaylistSubscribedEvent(
    UUID playlistOwnerId,
    UUID subscriberId,
    String playlistTitle
) {
}