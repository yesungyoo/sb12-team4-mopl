package com.mopl.notification.repository;

import com.mopl.core.domain.notification.entity.Notification;
import com.mopl.core.domain.user.entity.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationRepositoryCustom {

  List<Notification> findByReceiverWithCursor(
      User receiver, UUID idAfter, LocalDateTime cursor, int size, String sortDirection
  );

  long countByReceiver(User receiver);
}