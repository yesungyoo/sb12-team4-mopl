package com.mopl.auth.dto;

import com.mopl.user.dto.UserResponse;

public record AuthResponse(
        UserResponse userDto,
        String accessToken
) {
}