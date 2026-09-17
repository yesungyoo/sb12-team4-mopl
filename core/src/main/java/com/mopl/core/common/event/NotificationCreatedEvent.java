package com.mopl.core.common.event;

import java.util.UUID;

public record NotificationCreatedEvent(
    UUID notificationId
) {
}