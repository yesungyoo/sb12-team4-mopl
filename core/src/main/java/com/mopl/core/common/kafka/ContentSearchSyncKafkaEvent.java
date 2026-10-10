package com.mopl.core.common.kafka;

import java.util.UUID;

public record ContentSearchSyncKafkaEvent(
        UUID contentId,
        boolean deleted
) {
}
