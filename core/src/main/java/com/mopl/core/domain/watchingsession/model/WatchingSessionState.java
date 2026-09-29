package com.mopl.core.domain.watchingsession.model;

import java.time.Instant;
import java.util.UUID;

public record WatchingSessionState(
	UUID id,
	UUID watcherId,
	UUID contentId,
	String webSocketSessionId,
	String subscriptionId,
	Instant createdAt
) {
}
