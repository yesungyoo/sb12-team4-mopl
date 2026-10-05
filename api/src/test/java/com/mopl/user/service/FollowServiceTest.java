package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.common.exception.MoplException;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.common.event.FollowCreatedEvent;
import com.mopl.core.domain.user.entity.Follow;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.dto.FollowResponse;
import com.mopl.user.repository.FollowRepository;
import com.mopl.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private FollowService followService;

    private User userWithId(UUID id) {
        User user = new User(id + "@mopl.com", null, "사용자", null, UserRole.USER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Follow followWithId(UUID followId, User follower, User followee) {
        Follow follow = new Follow(follower, followee);
        ReflectionTestUtils.setField(follow, "id", followId);
        return follow;
    }

    @Test
    @DisplayName("팔로우하면 생성된 팔로우 id 와 두 사용자 id 를 반환하고 이벤트를 발행한다")
    void follow() {
        UUID followerId = UUID.randomUUID();
        UUID followeeId = UUID.randomUUID();
        UUID followId = UUID.randomUUID();
        User follower = userWithId(followerId);
        User followee = userWithId(followeeId);
        when(userRepository.findByIdAndDeletedAtIsNull(followerId)).thenReturn(Optional.of(follower));
        when(userRepository.findByIdAndDeletedAtIsNull(followeeId)).thenReturn(Optional.of(followee));
        when(followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)).thenReturn(false);
        when(followRepository.saveAndFlush(any(Follow.class)))
                .thenReturn(followWithId(followId, follower, followee));

        FollowResponse response = followService.follow(followerId, followeeId);

        assertThat(response.id()).isEqualTo(followId);
        assertThat(response.followerId()).isEqualTo(followerId);
        assertThat(response.followeeId()).isEqualTo(followeeId);
        verify(eventPublisher).publishEvent(any(FollowCreatedEvent.class));
    }

    @Test
    @DisplayName("자기 자신은 팔로우할 수 없다")
    void selfFollow() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> followService.follow(id, id)).isInstanceOf(MoplException.class);

        verify(followRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이미 팔로우 중이면 저장하지 않는다")
    void alreadyFollowing() {
        UUID followerId = UUID.randomUUID();
        UUID followeeId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(followerId)).thenReturn(Optional.of(userWithId(followerId)));
        when(userRepository.findByIdAndDeletedAtIsNull(followeeId)).thenReturn(Optional.of(userWithId(followeeId)));
        when(followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)).thenReturn(true);

        assertThatThrownBy(() -> followService.follow(followerId, followeeId)).isInstanceOf(MoplException.class);

        verify(followRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동시 요청으로 DB 유니크 제약에 걸리면 이벤트를 발행하지 않고 예외로 변환한다")
    void concurrentFollow() {
        UUID followerId = UUID.randomUUID();
        UUID followeeId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(followerId)).thenReturn(Optional.of(userWithId(followerId)));
        when(userRepository.findByIdAndDeletedAtIsNull(followeeId)).thenReturn(Optional.of(userWithId(followeeId)));
        when(followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)).thenReturn(false);
        when(followRepository.saveAndFlush(any(Follow.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> followService.follow(followerId, followeeId)).isInstanceOf(MoplException.class);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("본인의 팔로우는 followId 로 취소할 수 있다")
    void unfollow() {
        UUID requesterId = UUID.randomUUID();
        UUID followId = UUID.randomUUID();
        Follow follow = followWithId(followId, userWithId(requesterId), userWithId(UUID.randomUUID()));
        when(followRepository.findById(followId)).thenReturn(Optional.of(follow));

        followService.unfollow(requesterId, followId);

        verify(followRepository).delete(follow);
    }

    @Test
    @DisplayName("존재하지 않는 followId 면 삭제하지 않는다")
    void unfollowNotFound() {
        UUID followId = UUID.randomUUID();
        when(followRepository.findById(followId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.unfollow(UUID.randomUUID(), followId))
                .isInstanceOf(MoplException.class);

        verify(followRepository, never()).delete(any(Follow.class));
    }

    @Test
    @DisplayName("다른 사람의 팔로우는 취소할 수 없다 (삭제하지 않는다)")
    void unfollowOthers() {
        UUID ownerId = UUID.randomUUID();
        UUID followId = UUID.randomUUID();
        Follow follow = followWithId(followId, userWithId(ownerId), userWithId(UUID.randomUUID()));
        when(followRepository.findById(followId)).thenReturn(Optional.of(follow));

        assertThatThrownBy(() -> followService.unfollow(UUID.randomUUID(), followId))
                .isInstanceOf(MoplException.class);

        verify(followRepository, never()).delete(any(Follow.class));
    }

    @Test
    @DisplayName("팔로워 수를 반환한다")
    void countFollowers() {
        UUID followeeId = UUID.randomUUID();
        when(followRepository.countByFolloweeId(followeeId)).thenReturn(7L);

        assertThat(followService.countFollowers(followeeId)).isEqualTo(7L);
    }

    @Test
    @DisplayName("내가 팔로우 중이면 팔로우 관계를 반환한다")
    void followedByMe() {
        UUID requesterId = UUID.randomUUID();
        UUID followeeId = UUID.randomUUID();
        UUID followId = UUID.randomUUID();
        Follow follow = followWithId(followId, userWithId(requesterId), userWithId(followeeId));
        when(followRepository.findByFollowerIdAndFolloweeId(requesterId, followeeId)).thenReturn(Optional.of(follow));

        FollowResponse response = followService.getFollowedByMe(requesterId, followeeId);

        assertThat(response.id()).isEqualTo(followId);
        assertThat(response.followerId()).isEqualTo(requesterId);
        assertThat(response.followeeId()).isEqualTo(followeeId);
    }

    @Test
    @DisplayName("내가 팔로우하지 않았으면 예외(404)를 던진다")
    void followedByMeNotFollowing() {
        UUID requesterId = UUID.randomUUID();
        UUID followeeId = UUID.randomUUID();
        when(followRepository.findByFollowerIdAndFolloweeId(requesterId, followeeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.getFollowedByMe(requesterId, followeeId))
                .isInstanceOf(MoplException.class);
    }
}