package com.mopl.realtime.watchingsession.dto;

import com.mopl.realtime.common.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record WatchingSessionDto(
	UUID id,
	Instant createdAt,
	UserSummary watcher,
	WatchingContentSummary content
) {
}