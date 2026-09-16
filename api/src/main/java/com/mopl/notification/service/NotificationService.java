package com.mopl.notification.service;

import com.mopl.common.exception.notification.NotificationNotFoundException;
import com.mopl.core.common.enums.NotificationType;
import com.mopl.core.domain.notification.entity.NotificationPreference;
import com.mopl.notification.repository.NotificationPreferenceRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.enums.NotificationLevel;
import com.mopl.core.domain.notification.entity.Notification;
import com.mopl.core.domain.user.entity.User;
import com.mopl.notification.dto.NotificationResponse;
import com.mopl.notification.repository.NotificationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final UserRepository userRepository;
  private final NotificationPreferenceRepository preferenceRepository;

  public CursorResponse<NotificationResponse> getNotifications(
      UUID userId, LocalDateTime cursor, UUID idAfter, int limit, String sortBy, String sortDirection
  ) {
    User receiver = userRepository.findById(userId)
        .orElseThrow(NotificationNotFoundException::new);

    List<Notification> notifications =
        notificationRepository.findByReceiverWithCursor(receiver, idAfter, cursor, limit + 1, sortDirection);

    boolean hasNext = notifications.size() > limit;
    List<Notification> content = hasNext ? notifications.subList(0, limit) : notifications;

    List<NotificationResponse> data = content.stream()
        .map(NotificationResponse::from)
        .collect(Collectors.toList());

    String nextCursor = hasNext ? content.get(content.size() - 1).getCreatedAt().toString() : null;
    String nextIdAfter = hasNext ? content.get(content.size() - 1).getId().toString() : null;

    long totalCount = notificationRepository.countByReceiver(receiver);

    return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, totalCount, sortBy, sortDirection);
  }

  @Transactional
  public void readNotification(UUID userId, UUID notificationId) {
    Notification notification = notificationRepository.findById(notificationId)
        .orElseThrow(NotificationNotFoundException::new);

    if (!notification.getReceiver().getId().equals(userId)) {
      throw new NotificationNotFoundException();
    }

    if (notification.isRead()) {
      return;
    }

    notification.markAsRead();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void createNotification(User receiver, String title, String content, NotificationLevel level, NotificationType type) {
    boolean enabled = preferenceRepository.findByUserAndType(receiver, type)
        .map(NotificationPreference::isEnabled)
        .orElse(true);

    if (!enabled) {
      return;
    }

    Notification notification = new Notification(receiver, title, content, level);
    notificationRepository.save(notification);
  }
}