package com.mopl.realtime.watchingsession.service;

import com.mopl.core.domain.watchingsession.model.WatchingSessionState;

import java.util.Optional;
import java.util.Set;

public record WatchingSessionJoinResult(
	WatchingSessionState joinedSession,
	Optional<WatchingSessionState> leftSession,
	Set<String> leftSubscriptionIds
) {

	public WatchingSessionJoinResult {
		leftSubscriptionIds = Set.copyOf(leftSubscriptionIds);
	}
}