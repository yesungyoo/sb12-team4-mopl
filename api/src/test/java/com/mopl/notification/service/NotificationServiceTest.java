package com.mopl.notification.service;

import com.mopl.common.exception.notification.NotificationNotFoundException;
import com.mopl.core.common.dto.CursorResponse;
import static org.mockito.ArgumentMatchers.eq;
import com.mopl.core.common.enums.NotificationLevel;
import com.mopl.core.common.enums.NotificationType;
import com.mopl.core.common.event.NotificationCreatedEvent;
import com.mopl.core.domain.notification.entity.Notification;
import com.mopl.core.domain.notification.entity.NotificationPreference;
import com.mopl.core.domain.user.entity.User;
import com.mopl.notification.sse.SseEmitterManager;
import com.mopl.user.repository.UserRepository;
import com.mopl.notification.dto.NotificationResponse;
import com.mopl.notification.repository.NotificationPreferenceRepository;
import com.mopl.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  @Mock
  private NotificationRepository notificationRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private NotificationPreferenceRepository preferenceRepository;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  @InjectMocks
  private NotificationService notificationService;

  @Nested
  @DisplayName("알림 목록 조회")
  class GetNotifications {

    @Test
    @DisplayName("성공 - 유저의 알림 목록을 커서 기준으로 조회한다")
    void 알림_목록_조회_성공() {
      // given
      UUID userId = UUID.randomUUID();
      User user = mock(User.class);
      given(user.getId()).willReturn(userId);

      Notification notification = mock(Notification.class);
      given(notification.getId()).willReturn(UUID.randomUUID());
      given(notification.getCreatedAt()).willReturn(LocalDateTime.now());
      given(notification.getReceiver()).willReturn(user);
      given(notification.getTitle()).willReturn("팔로우 알림");
      given(notification.getContent()).willReturn("OO님이 팔로우했습니다.");
      given(notification.getLevel()).willReturn(NotificationLevel.INFO);

      given(userRepository.findById(userId)).willReturn(Optional.of(user));
      given(notificationRepository.findByReceiverWithCursor(user, null, null, 21, "DESCENDING"))
          .willReturn(List.of(notification));
      given(notificationRepository.countByReceiver(user)).willReturn(1L);

      // when
      CursorResponse<NotificationResponse> response =
          notificationService.getNotifications(userId, null, null, 20, "createdAt", "DESCENDING");

      // then
      assertThat(response.data()).hasSize(1);
      assertThat(response.data().get(0).title()).isEqualTo("팔로우 알림");
      assertThat(response.hasNext()).isFalse();
      assertThat(response.totalCount()).isEqualTo(1L);
      assertThat(response.sortBy()).isEqualTo("createdAt");
      assertThat(response.sortDirection()).isEqualTo("DESCENDING");
    }

    @Test
    @DisplayName("성공 - 다음 페이지가 있으면 hasNext가 true이고 nextCursor가 채워진다")
    void 알림_목록_조회_다음페이지_존재() {
      // given
      UUID userId = UUID.randomUUID();
      User user = mock(User.class);
      given(user.getId()).willReturn(userId);

      List<Notification> notifications = List.of(
          mockNotification(user), mockNotification(user), mockNotification(user)
      );

      given(userRepository.findById(userId)).willReturn(Optional.of(user));
      given(notificationRepository.findByReceiverWithCursor(user, null, null, 3, "DESCENDING"))
          .willReturn(notifications);
      given(notificationRepository.countByReceiver(user)).willReturn(10L);

      // when
      CursorResponse<NotificationResponse> response =
          notificationService.getNotifications(userId, null, null, 2, "createdAt", "DESCENDING");

      // then
      assertThat(response.data()).hasSize(2);
      assertThat(response.hasNext()).isTrue();
      assertThat(response.nextCursor()).isNotNull();
      assertThat(response.nextIdAfter()).isNotNull();
    }

    @Test
    @DisplayName("실패 - 존재하지 않는 유저면 예외가 발생한다")
    void 알림_목록_조회_실패_유저_없음() {
      // given
      UUID userId = UUID.randomUUID();
      given(userRepository.findById(userId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() ->
          notificationService.getNotifications(userId, null, null, 20, "createdAt", "DESCENDING")
      ).isInstanceOf(NotificationNotFoundException.class);
    }

    private Notification mockNotification(User receiver) {
      Notification notification = mock(Notification.class);
      lenient().when(notification.getId()).thenReturn(UUID.randomUUID());
      lenient().when(notification.getCreatedAt()).thenReturn(LocalDateTime.now());
      lenient().when(notification.getReceiver()).thenReturn(receiver);
      lenient().when(notification.getTitle()).thenReturn("알림");
      lenient().when(notification.getContent()).thenReturn("내용");
      lenient().when(notification.getLevel()).thenReturn(NotificationLevel.INFO);
      return notification;
    }
  }

  @Nested
  @DisplayName("알림 읽음 처리")
  class ReadNotification {

    @Test
    @DisplayName("성공 - 본인 알림을 읽음 처리한다")
    void 알림_읽음_처리_성공() {
      // given
      UUID userId = UUID.randomUUID();
      UUID notificationId = UUID.randomUUID();

      User user = mock(User.class);
      given(user.getId()).willReturn(userId);

      Notification notification = mock(Notification.class);
      given(notification.getReceiver()).willReturn(user);
      given(notification.isRead()).willReturn(false);

      given(notificationRepository.findById(notificationId)).willReturn(Optional.of(notification));

      // when
      notificationService.readNotification(userId, notificationId);

      // then
      then(notification).should().markAsRead();
    }

    @Test
    @DisplayName("성공 - 이미 읽은 알림이면 다시 처리하지 않는다")
    void 알림_읽음_처리_이미_읽음() {
      // given
      UUID userId = UUID.randomUUID();
      UUID notificationId = UUID.randomUUID();

      User user = mock(User.class);
      given(user.getId()).willReturn(userId);

      Notification notification = mock(Notification.class);
      given(notification.getReceiver()).willReturn(user);
      given(notification.isRead()).willReturn(true);

      given(notificationRepository.findById(notificationId)).willReturn(Optional.of(notification));

      // when
      notificationService.readNotification(userId, notificationId);

      // then
      then(notification).should(never()).markAsRead();
    }

    @Test
    @DisplayName("실패 - 존재하지 않는 알림이면 예외가 발생한다")
    void 알림_읽음_처리_실패_알림_없음() {
      // given
      UUID userId = UUID.randomUUID();
      UUID notificationId = UUID.randomUUID();

      given(notificationRepository.findById(notificationId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() ->
          notificationService.readNotification(userId, notificationId)
      ).isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    @DisplayName("실패 - 본인 알림이 아니면 예외가 발생한다")
    void 알림_읽음_처리_실패_본인_알림_아님() {
      // given
      UUID userId = UUID.randomUUID();
      UUID otherUserId = UUID.randomUUID();
      UUID notificationId = UUID.randomUUID();

      User otherUser = mock(User.class);
      given(otherUser.getId()).willReturn(otherUserId);

      Notification notification = mock(Notification.class);
      given(notification.getReceiver()).willReturn(otherUser);

      given(notificationRepository.findById(notificationId)).willReturn(Optional.of(notification));

      // when & then
      assertThatThrownBy(() ->
          notificationService.readNotification(userId, notificationId)
      ).isInstanceOf(NotificationNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("알림 생성")
  class CreateNotification {

    @Test
    @DisplayName("성공 - 알림 설정이 켜져 있으면 알림이 생성되고 이벤트가 발행된다")
    void 알림_생성_성공() {
      // given
      User receiver = mock(User.class);
      given(preferenceRepository.findByUserAndType(receiver, NotificationType.FOLLOW))
          .willReturn(Optional.empty());

      Notification savedNotification = mock(Notification.class);
      given(savedNotification.getId()).willReturn(UUID.randomUUID());
      given(notificationRepository.save(any())).willReturn(savedNotification);

      // when
      notificationService.createNotification(
          receiver, "팔로우 알림", "OO님이 팔로우했습니다.", NotificationLevel.INFO, NotificationType.FOLLOW
      );

      // then
      then(notificationRepository).should().save(any());
      then(eventPublisher).should().publishEvent(any(NotificationCreatedEvent.class));
    }

    @Test
    @DisplayName("성공 - 알림 설정이 꺼져 있으면 알림이 생성되지 않는다")
    void 알림_생성_스킵_설정_꺼짐() {
      // given
      User receiver = mock(User.class);
      NotificationPreference preference = mock(NotificationPreference.class);
      given(preference.isEnabled()).willReturn(false);
      given(preferenceRepository.findByUserAndType(receiver, NotificationType.FOLLOW))
          .willReturn(Optional.of(preference));

      // when
      notificationService.createNotification(
          receiver, "팔로우 알림", "OO님이 팔로우했습니다.", NotificationLevel.INFO, NotificationType.FOLLOW
      );

      // then
      then(notificationRepository).should(never()).save(any());
    }
  }
}