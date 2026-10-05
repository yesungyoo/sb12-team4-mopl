package com.mopl.user.service;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.common.event.FollowCreatedEvent;
import com.mopl.core.domain.user.entity.Follow;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.dto.FollowListResponse;
import com.mopl.user.dto.FollowResponse;
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

    /** followerId 가 followeeId 를 팔로우한다. 생성된 팔로우 관계(id 포함)를 반환한다. */
    @Transactional
    public FollowResponse follow(UUID followerId, UUID followeeId) {
        if (followerId.equals(followeeId)) {
            throw new MoplException(UserErrorCode.SELF_FOLLOW_NOT_ALLOWED);
        }
        User follower = findActiveUserOrThrow(followerId);
        User followee = findActiveUserOrThrow(followeeId);

        if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            throw new MoplException(UserErrorCode.ALREADY_FOLLOWING);
        }

        Follow saved;
        try {
            // existsBy 체크와 save 사이의 짧은 순간에 동일 팔로우 요청이 동시에 들어오면
            // 위 exists 체크를 둘 다 통과할 수 있다. DB UNIQUE 제약(uk_follows_follower_followee)이
            // 최종 방어선 역할을 하는데, saveAndFlush 로 즉시 반영해 그 순간에 발생하는
            // DataIntegrityViolationException 을 여기서 잡아 ALREADY_FOLLOWING 으로 변환한다.
            saved = followRepository.saveAndFlush(new Follow(follower, followee));
        } catch (DataIntegrityViolationException e) {
            throw new MoplException(UserErrorCode.ALREADY_FOLLOWING);
        }
        eventPublisher.publishEvent(new FollowCreatedEvent(followeeId, followerId));
        return new FollowResponse(saved.getId(), followeeId, followerId);
    }

    /**
     * 팔로우 취소. 명세(DELETE /api/follows/{followId})에 따라 팔로우 관계의 id 로 식별하고,
     * 요청자 본인의 팔로우만 취소할 수 있다.
     */
    @Transactional
    public void unfollow(UUID requesterId, UUID followId) {
        Follow follow = followRepository.findById(followId)
                .orElseThrow(() -> new MoplException(UserErrorCode.FOLLOW_NOT_FOUND));
        if (!follow.getFollower().getId().equals(requesterId)) {
            throw new MoplException(UserErrorCode.ACCESS_DENIED, "본인의 팔로우만 취소할 수 있습니다.");
        }
        followRepository.delete(follow);
    }

    /** 특정 유저의 팔로워 수 (GET /api/follows/count) */
    public long countFollowers(UUID followeeId) {
        return followRepository.countByFolloweeId(followeeId);
    }

    /** 내가 특정 유저를 팔로우 중이면 그 관계를, 아니면 FOLLOW_NOT_FOUND(404)를 던진다. */
    public FollowResponse getFollowedByMe(UUID requesterId, UUID followeeId) {
        Follow follow = followRepository.findByFollowerIdAndFolloweeId(requesterId, followeeId)
                .orElseThrow(() -> new MoplException(UserErrorCode.FOLLOW_NOT_FOUND));
        return new FollowResponse(follow.getId(), followeeId, requesterId);
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