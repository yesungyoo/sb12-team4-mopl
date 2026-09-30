package com.mopl.core.common.event;

import java.util.UUID;

public record RecommendationPreferenceChangedEvent(
        UUID userId
) {
}
