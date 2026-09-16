package com.mopl.notification.dto;

import com.mopl.core.domain.notification.entity.NotificationPreference;

public record NotificationPreferenceResponse(
    String type,
    Boolean enabled
) {
  public static NotificationPreferenceResponse from(NotificationPreference preference) {
    return new NotificationPreferenceResponse(
        preference.getType().name(),
        preference.isEnabled()
    );
  }
}