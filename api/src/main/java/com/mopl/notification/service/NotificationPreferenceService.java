package com.mopl.notification.service;

import com.mopl.common.exception.notification.NotificationNotFoundException;
import com.mopl.core.common.enums.NotificationType;
import com.mopl.core.domain.notification.entity.NotificationPreference;
import com.mopl.core.domain.user.entity.User;
import com.mopl.notification.dto.NotificationPreferenceResponse;
import com.mopl.notification.repository.NotificationPreferenceRepository;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationPreferenceService {

  private final NotificationPreferenceRepository preferenceRepository;
  private final UserRepository userRepository;

  public List<NotificationPreferenceResponse> getPreferences(UUID userId) {
    User user = userRepository.findById(userId)
        .orElseThrow(NotificationNotFoundException::new);

    List<NotificationPreference> saved = preferenceRepository.findByUser(user);

    Map<NotificationType, Boolean> savedMap = saved.stream()
        .collect(Collectors.toMap(NotificationPreference::getType, NotificationPreference::isEnabled));

    // 저장된 설정이 없는 타입은 기본값 true로 채움 (옵트아웃 방식)
    return Arrays.stream(NotificationType.values())
        .map(type -> new NotificationPreferenceResponse(
            type.name(),
            savedMap.getOrDefault(type, true)
        ))
        .collect(Collectors.toList());
  }

  @Transactional
  public void updatePreference(UUID userId, NotificationType type, boolean enabled) {
    User user = userRepository.findById(userId)
        .orElseThrow(NotificationNotFoundException::new);

    NotificationPreference preference = preferenceRepository.findByUserAndType(user, type)
        .orElseGet(() -> new NotificationPreference(user, type));

    if (enabled) {
      preference.turnOn();
    } else {
      preference.turnOff();
    }

    preferenceRepository.save(preference);
  }
}