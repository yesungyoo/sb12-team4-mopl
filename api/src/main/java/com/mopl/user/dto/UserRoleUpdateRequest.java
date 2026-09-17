package com.mopl.user.dto;

import com.mopl.core.common.enums.UserRole;
import jakarta.validation.constraints.NotNull;

public record UserRoleUpdateRequest(
        @NotNull UserRole role
) {
}
