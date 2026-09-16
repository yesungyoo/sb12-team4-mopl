package com.mopl.content.search.event;

import java.util.UUID;

public record ContentSearchSyncEvent(
        UUID contentId,
        boolean deleted
) {
}
