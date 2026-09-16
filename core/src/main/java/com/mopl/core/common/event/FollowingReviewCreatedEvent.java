package com.mopl.core.common.event;

import java.util.List;
import java.util.UUID;

public record FollowingReviewCreatedEvent(
    List<UUID> followerIds,
    String reviewerName,
    String contentTitle
) {
}