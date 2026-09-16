package com.mopl.core.common.event;

import java.util.UUID;

public record RoleChangedEvent(
    UUID userId,
    String newRole
) {
}