package com.mopl.review.dto;

import com.mopl.core.domain.user.entity.User;

import java.util.UUID;

public record ReviewAuthorResponse(
        UUID id,
        String name,
        String profileImageUrl
) {

    private static final String DELETED_USER_NAME = "탈퇴한 사용자";

    public static ReviewAuthorResponse from(User user) {
        if (user.getDeletedAt() != null) {
            return new ReviewAuthorResponse(
                    user.getId(),
                    DELETED_USER_NAME,
                    null
            );
        }

        return new ReviewAuthorResponse(
                user.getId(),
                user.getName(),
                user.getProfileImageUrl()
        );
    }
}
