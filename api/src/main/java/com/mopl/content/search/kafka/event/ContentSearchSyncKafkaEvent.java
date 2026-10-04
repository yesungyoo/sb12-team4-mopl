package com.mopl.content.search.kafka.event;

import java.util.UUID;

public record ContentSearchSyncKafkaEvent(
        UUID contentId,
        boolean deleted
) {
}
