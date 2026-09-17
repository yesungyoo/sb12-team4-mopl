package com.mopl.notification.controller;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.notification.dto.NotificationResponse;
import com.mopl.notification.service.NotificationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationService notificationService;

  @GetMapping
  public ResponseEntity<CursorResponse<NotificationResponse>> getNotifications(
      @RequestParam(required = false) LocalDateTime cursor,
      @RequestParam(required = false) UUID idAfter,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "createdAt") String sortBy,
      @RequestParam(defaultValue = "DESCENDING") String sortDirection
  ) {
    UUID userId = SecurityUtil.getCurrentUserId();
    CursorResponse<NotificationResponse> notifications =
        notificationService.getNotifications(userId, cursor, idAfter, limit, sortBy, sortDirection);
    return ResponseEntity.ok(notifications);
  }

  @DeleteMapping("/{notificationId}")
  public ResponseEntity<Void> deleteNotification(@PathVariable UUID notificationId) {
    UUID userId = SecurityUtil.getCurrentUserId();
    notificationService.readNotification(userId, notificationId);
    return ResponseEntity.noContent().build();
  }

}
