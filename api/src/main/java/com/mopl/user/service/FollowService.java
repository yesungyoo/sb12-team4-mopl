package com.mopl.user.service;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.common.event.FollowCreatedEvent;
import com.mopl.core.domain.user.entity.Follow;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.dto.FollowListResponse;
import com.mopl.user.dto.UserResponse;
import com.mopl.user.repository.FollowRepository;
import com.mopl.user.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void follow(UUID followerId, UUID followeeId) {
        if (followerId.equals(followeeId)) {
            throw new MoplException(UserErrorCode.SELF_FOLLOW_NOT_ALLOWED);
        }
        User follower = findActiveUserOrThrow(followerId);
        User followee = findActiveUserOrThrow(followeeId);

        if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            throw new MoplException(UserErrorCode.ALREADY_FOLLOWING);
        }

        try {
            // existsBy 체크와 save 사이의 짧은 순간에 동일 팔로우 요청이 동시에 들어오면
            // 위 exists 체크를 둘 다 통과할 수 있다. DB UNIQUE 제약(uk_follows_follower_followee)이
            // 최종 방어선 역할을 하는데, saveAndFlush 로 즉시 반영해 그 순간에 발생하는
            // DataIntegrityViolationException 을 여기서 잡아 ALREADY_FOLLOWING 으로 변환한다.
            followRepository.saveAndFlush(new Follow(follower, followee));
        } catch (DataIntegrityViolationException e) {
            throw new MoplException(UserErrorCode.ALREADY_FOLLOWING);
        }
        eventPublisher.publishEvent(new FollowCreatedEvent(followeeId, followerId));
    }

    @Transactional
    public void unfollow(UUID followerId, UUID followeeId) {
        if (!followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            throw new MoplException(UserErrorCode.FOLLOW_NOT_FOUND);
        }
        followRepository.deleteByFollowerIdAndFolloweeId(followerId, followeeId);
    }

    public FollowListResponse getFollowers(UUID followeeId, int page, int size) {
        findActiveUserOrThrow(followeeId);
        Page<User> result = followRepository.findFollowers(followeeId, PageRequest.of(page, size));
        return toResponse(result);
    }

    public FollowListResponse getFollowing(UUID followerId, int page, int size) {
        findActiveUserOrThrow(followerId);
        Page<User> result = followRepository.findFollowing(followerId, PageRequest.of(page, size));
        return toResponse(result);
    }

    private FollowListResponse toResponse(Page<User> result) {
        return new FollowListResponse(
                result.getContent().stream().map(UserResponse::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.hasNext()
        );
    }

    private User findActiveUserOrThrow(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new MoplException(UserErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다. userId=" + userId));
    }
}