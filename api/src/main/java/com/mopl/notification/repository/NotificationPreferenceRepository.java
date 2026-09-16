package com.mopl.notification.repository;

import com.mopl.core.common.enums.NotificationType;
import com.mopl.core.domain.notification.entity.NotificationPreference;
import com.mopl.core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

  List<NotificationPreference> findByUser(User user);

  Optional<NotificationPreference> findByUserAndType(User user, NotificationType type);
}