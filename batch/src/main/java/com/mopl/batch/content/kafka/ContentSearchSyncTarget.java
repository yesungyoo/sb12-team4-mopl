package com.mopl.batch.content.kafka;

import java.util.UUID;

public record ContentSearchSyncTarget(
        UUID contentId,
        boolean deleted
) {
}
