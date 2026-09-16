package com.mopl.core.common.event;

import java.util.UUID;

public record PlaylistContentAddedEvent(
    UUID subscriberId,
    String playlistTitle
) {
}