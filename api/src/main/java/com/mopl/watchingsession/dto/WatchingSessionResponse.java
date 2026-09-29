package com.mopl.watchingsession.dto;

import java.time.Instant;
import java.util.UUID;

public record WatchingSessionResponse(
	UUID id,
	Instant createdAt,
	WatchingUserSummary watcher,
	WatchingContentSummary content
) {
}