package com.mopl.user.dto;

import java.util.UUID;

/** 팔로우 관계 응답 (명세: FollowDto). id 는 팔로우 취소(DELETE /api/follows/{followId})에 쓰인다. */
public record FollowResponse(
        UUID id,
        UUID followeeId,
        UUID followerId
) {
}