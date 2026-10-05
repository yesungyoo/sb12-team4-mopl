package com.mopl.user.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** POST /api/follows 요청 바디 (명세: FollowRequest) */
public record FollowRequest(
        @NotNull(message = "followeeId 는 필수입니다.")
        UUID followeeId
) {
}