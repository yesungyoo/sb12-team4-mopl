package com.mopl.notification.listener;

import com.mopl.core.common.enums.NotificationLevel;
import com.mopl.core.common.enums.NotificationType;
import com.mopl.core.common.event.DirectMessageReceivedEvent;
import com.mopl.core.common.event.FollowCreatedEvent;
import com.mopl.core.common.event.FollowingPlaylistCreatedEvent;
import com.mopl.core.common.event.FollowingReviewCreatedEvent;
import com.mopl.core.common.event.FollowingWatchStartedEvent;
import com.mopl.core.common.event.PlaylistContentAddedEvent;
import com.mopl.core.common.event.PlaylistSubscribedEvent;
import com.mopl.core.common.event.RoleChangedEvent;
import com.mopl.core.domain.user.entity.User;
import com.mopl.notification.service.NotificationService;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.List;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

  private final NotificationService notificationService;
  private final UserRepository userRepository;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleFollowed(FollowCreatedEvent event) {
    safeCreateNotification(
        event.followeeId(), "새로운 팔로워", "회원님을 팔로우했습니다.",
        NotificationLevel.INFO, NotificationType.FOLLOW, event
    );
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleRoleChanged(RoleChangedEvent event) {
    safeCreateNotification(
        event.userId(), "권한 변경 안내", "회원님의 권한이 " + event.newRole() + "(으)로 변경되었습니다.",
        NotificationLevel.WARNING, NotificationType.ROLE_CHANGED, event
    );
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handlePlaylistSubscribed(PlaylistSubscribedEvent event) {
    safeCreateNotification(
        event.playlistOwnerId(), "플레이리스트 구독 알림",
        "회원님의 플레이리스트 '" + event.playlistTitle() + "'을(를) 구독했습니다.",
        NotificationLevel.INFO, NotificationType.PLAYLIST_SUBSCRIBED, event
    );
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handlePlaylistContentAdded(PlaylistContentAddedEvent event) {
    safeCreateNotification(
        event.subscriberId(), "새 콘텐츠 추가 알림",
        "구독 중인 플레이리스트 '" + event.playlistTitle() + "'에 콘텐츠가 추가되었습니다.",
        NotificationLevel.INFO, NotificationType.PLAYLIST_CONTENT_ADDED, event
    );
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleDirectMessageReceived(DirectMessageReceivedEvent event) {
    safeCreateNotification(
        event.receiverId(), "새 메시지 도착", "새로운 DM이 도착했습니다.",
        NotificationLevel.INFO, NotificationType.DIRECT_MESSAGE, event
    );
  }

  private void safeCreateNotification(
      UUID receiverId, String title, String content, NotificationLevel level, NotificationType type, Object event
  ) {
    try {
      User receiver = userRepository.findById(receiverId)
          .orElseThrow(() -> new IllegalStateException("User not found: " + receiverId));
      notificationService.createNotification(receiver, title, content, level, type);
    } catch (Exception e) {
      log.warn("알림 생성 실패 - event: {}, receiverId: {}", event, receiverId, e);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleFollowingPlaylistCreated(FollowingPlaylistCreatedEvent event) {
    notifyFollowers(
        event.followerIds(), "팔로우 중인 사용자의 새 플레이리스트",
        event.creatorName() + "님이 '" + event.playlistTitle() + "' 플레이리스트를 만들었습니다.",
        NotificationLevel.INFO, NotificationType.FOLLOWING_PLAYLIST_CREATED
    );
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleFollowingReviewCreated(FollowingReviewCreatedEvent event) {
    notifyFollowers(
        event.followerIds(), "팔로우 중인 사용자의 새 리뷰",
        event.reviewerName() + "님이 '" + event.contentTitle() + "'에 리뷰를 남겼습니다.",
        NotificationLevel.INFO, NotificationType.FOLLOWING_REVIEW_CREATED
    );
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleFollowingWatchStarted(FollowingWatchStartedEvent event) {
    notifyFollowers(
        event.followerIds(), "팔로우 중인 사용자가 시청 중",
        event.watcherName() + "님이 '" + event.contentTitle() + "'을(를) 시청 중입니다.",
        NotificationLevel.INFO, NotificationType.FOLLOWING_WATCH_STARTED
    );
  }

  private void notifyFollowers(List<UUID> followerIds, String title, String content, NotificationLevel level, NotificationType type) {
    for (UUID followerId : followerIds) {
      try {
        User follower = userRepository.findById(followerId)
            .orElseThrow(() -> new IllegalStateException("User not found: " + followerId));
        notificationService.createNotification(follower, title, content, level, type);
      } catch (Exception e) {
        log.warn("팔로워 알림 생성 실패 - followerId: {}", followerId, e);
      }
    }
  }
}