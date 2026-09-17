package com.mopl.user.dto;

import jakarta.validation.constraints.NotNull;

public record UserLockedUpdateRequest(
        @NotNull Boolean locked
) {
}

