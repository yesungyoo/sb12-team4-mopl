package com.mopl.content.search.event;

import java.util.UUID;

public record ContentSearchStatisticsSyncEvent(
        UUID contentId
) {
}
