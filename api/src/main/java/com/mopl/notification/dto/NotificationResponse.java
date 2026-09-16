package com.mopl.notification.dto;

import com.mopl.core.domain.notification.entity.Notification;
import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
    UUID id,
    LocalDateTime createdAt,
    UUID receiverId,
    String title,
    String content,
    String level
) {
  public static NotificationResponse from(Notification notification) {
    return new NotificationResponse(
        notification.getId(),
        notification.getCreatedAt(),
        notification.getReceiver().getId(),
        notification.getTitle(),
        notification.getContent(),
        notification.getLevel().name()
    );
  }
}
