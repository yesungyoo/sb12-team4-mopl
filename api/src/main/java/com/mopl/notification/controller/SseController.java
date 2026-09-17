package com.mopl.notification.controller;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.notification.dto.NotificationResponse;
import com.mopl.notification.repository.NotificationRepository;
import com.mopl.notification.sse.SseEmitterManager;
import com.mopl.core.domain.notification.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SseController {

  private final SseEmitterManager sseEmitterManager;
  private final NotificationRepository notificationRepository;
  private final UserRepository userRepository;

  @GetMapping(value = "/api/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter connect(
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
  ) {
    UUID userId = SecurityUtil.getCurrentUserId();
    SseEmitter emitter = sseEmitterManager.connect(userId);

    if (lastEventId != null) {
      resendMissedNotifications(userId, UUID.fromString(lastEventId));
    }

    return emitter;
  }

  private void resendMissedNotifications(UUID userId, UUID lastEventId) {
    User user = userRepository.findById(userId).orElse(null);
    if (user == null) {
      return;
    }

    Notification lastNotification = notificationRepository.findById(lastEventId).orElse(null);
    if (lastNotification == null) {
      return;
    }

    if (!lastNotification.getReceiver().getId().equals(userId)) {
      log.warn("Last-Event-ID가 다른 사용자의 알림입니다 - userId: {}, lastEventId: {}", userId, lastEventId);
      return;
    }

    List<Notification> missed = notificationRepository.findByReceiverWithCursor(
        user, lastEventId, lastNotification.getCreatedAt(), 100, "ASCENDING"
    );

    for (Notification notification : missed) {
      NotificationResponse response = NotificationResponse.from(notification);
      sseEmitterManager.send(userId, "notifications", response, notification.getId().toString());
    }
  }
}