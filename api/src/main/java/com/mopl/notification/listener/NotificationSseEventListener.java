package com.mopl.notification.listener;

import com.mopl.core.common.event.NotificationCreatedEvent;
import com.mopl.core.domain.notification.entity.Notification;
import com.mopl.notification.dto.NotificationResponse;
import com.mopl.notification.repository.NotificationRepository;
import com.mopl.notification.sse.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSseEventListener {

  private final NotificationRepository notificationRepository;
  private final SseEmitterManager sseEmitterManager;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleNotificationCreated(NotificationCreatedEvent event) {
    notificationRepository.findById(event.notificationId())
        .ifPresentOrElse(
            this::sendToSse,
            () -> log.warn("SSE 전송 대상 알림을 찾을 수 없음 - notificationId: {}", event.notificationId())
        );
  }

  private void sendToSse(Notification notification) {
    NotificationResponse response = NotificationResponse.from(notification);
    sseEmitterManager.send(
        notification.getReceiver().getId(),
        "notifications",
        response,
        notification.getId().toString()
    );
  }
}