package com.mopl.notification.controller;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.core.domain.notification.entity.Notification;
import com.mopl.core.domain.user.entity.User;
import com.mopl.notification.repository.NotificationRepository;
import com.mopl.notification.sse.SseEmitterManager;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.mopl.core.common.enums.NotificationLevel;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SseControllerTest {

  @Mock
  private SseEmitterManager sseEmitterManager;

  @Mock
  private NotificationRepository notificationRepository;

  @Mock
  private UserRepository userRepository;

  @InjectMocks
  private SseController sseController;

  private MockedStatic<SecurityUtil> securityUtilMock;

  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    securityUtilMock = mockStatic(SecurityUtil.class);
    securityUtilMock.when(SecurityUtil::getCurrentUserId).thenReturn(userId);
  }

  @AfterEach
  void tearDown() {
    securityUtilMock.close();
  }

  @Test
  void SSE_연결에_성공한다() {
    // given
    SseEmitter emitter = new SseEmitter();
    given(sseEmitterManager.connect(userId)).willReturn(emitter);

    // when
    SseEmitter result = sseController.connect(null);

    // then
    assertThat(result).isEqualTo(emitter);
    then(sseEmitterManager).should().connect(userId);
    then(notificationRepository).shouldHaveNoInteractions();
  }

  @Test
  void LastEventId가_있으면_누락된_알림을_재전송한다() {
    // given
    SseEmitter emitter = new SseEmitter();
    given(sseEmitterManager.connect(userId)).willReturn(emitter);

    User user = mock(User.class);
    given(userRepository.findById(userId)).willReturn(Optional.of(user));

    UUID lastEventId = UUID.randomUUID();
    Notification lastNotification = mock(Notification.class);
    given(lastNotification.getReceiver()).willReturn(user);
    given(user.getId()).willReturn(userId);
    given(lastNotification.getCreatedAt()).willReturn(LocalDateTime.now());
    given(notificationRepository.findById(lastEventId)).willReturn(Optional.of(lastNotification));

    Notification missed = mock(Notification.class);
    UUID missedId = UUID.randomUUID();
    given(missed.getId()).willReturn(missedId);
    given(missed.getReceiver()).willReturn(user);
    given(missed.getTitle()).willReturn("테스트 알림");
    given(missed.getContent()).willReturn("테스트 내용");
    given(missed.getLevel()).willReturn(NotificationLevel.INFO);
    given(missed.getCreatedAt()).willReturn(LocalDateTime.now());
    given(notificationRepository.findByReceiverWithCursor(
        eq(user), eq(lastEventId), any(), eq(100), eq("ASCENDING")
    )).willReturn(List.of(missed));

    // when
    sseController.connect(lastEventId.toString());

    // then
    then(sseEmitterManager).should().send(eq(userId), eq("notifications"), any(), eq(missedId.toString()));
  }

  @Test
  void 다른_사용자_소유의_LastEventId면_재전송하지_않는다() {
    // given
    SseEmitter emitter = new SseEmitter();
    given(sseEmitterManager.connect(userId)).willReturn(emitter);

    User user = mock(User.class);
    given(userRepository.findById(userId)).willReturn(Optional.of(user));

    UUID lastEventId = UUID.randomUUID();
    User anotherUser = mock(User.class);
    given(anotherUser.getId()).willReturn(UUID.randomUUID());

    Notification lastNotification = mock(Notification.class);
    given(lastNotification.getReceiver()).willReturn(anotherUser);
    given(notificationRepository.findById(lastEventId)).willReturn(Optional.of(lastNotification));

    // when
    sseController.connect(lastEventId.toString());

    // then
    then(sseEmitterManager).should(never()).send(any(), any(), any(), any());
  }
}