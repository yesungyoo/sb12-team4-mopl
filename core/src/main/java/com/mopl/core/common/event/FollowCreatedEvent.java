package com.mopl.core.common.event;

import java.util.UUID;

public record FollowCreatedEvent(
    UUID followeeId,   // 팔로우 당한 사람 (알림 받을 사람)
    UUID followerId    // 팔로우 한 사람
) {
}