package com.mopl.user.dto;

import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.user.entity.User;
import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        LocalDateTime createdAt,
        String email,
        String name,
        String profileImageUrl,
        UserRole role,
        boolean locked
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getCreatedAt(),
                user.getEmail(),
                user.getName(),
                user.getProfileImageUrl(),
                user.getRole(),
                user.isLocked()
        );
    }
}
