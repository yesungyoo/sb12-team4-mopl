package com.mopl.notification.controller;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.core.common.enums.NotificationType;
import com.mopl.notification.dto.NotificationPreferenceResponse;
import com.mopl.notification.dto.NotificationPreferenceUpdateRequest;
import com.mopl.notification.service.NotificationPreferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

  private final NotificationPreferenceService preferenceService;

  @GetMapping
  public ResponseEntity<List<NotificationPreferenceResponse>> getPreferences(
  ) {
    UUID userId = SecurityUtil.getCurrentUserId();
    return ResponseEntity.ok(preferenceService.getPreferences(userId));
  }

  @PatchMapping("/{type}")
  public ResponseEntity<Void> updatePreference(
      @PathVariable NotificationType type,
      @Valid @RequestBody NotificationPreferenceUpdateRequest request
  ) {
    UUID userId = SecurityUtil.getCurrentUserId();
    preferenceService.updatePreference(userId, type, request.enabled());
    return ResponseEntity.noContent().build();
  }
}