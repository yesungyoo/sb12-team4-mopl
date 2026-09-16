package com.mopl.notification.repository;

import com.mopl.core.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, NotificationRepositoryCustom {

}