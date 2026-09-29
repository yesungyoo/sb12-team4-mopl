package com.mopl.realtime.watchingsession.dto;

public record WatchingSessionChange(
	ChangeType type,
	WatchingSessionDto watchingSession,
	long watcherCount
) {
}