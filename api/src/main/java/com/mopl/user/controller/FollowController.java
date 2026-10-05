package com.mopl.user.controller;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.user.dto.FollowListResponse;
import com.mopl.user.dto.FollowRequest;
import com.mopl.user.dto.FollowResponse;
import com.mopl.user.service.FollowService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 경로/요청/응답은 Swagger 명세(팔로우 관리)를 따른다.
 * - POST   /api/follows                      팔로우
 * - DELETE /api/follows/{followId}           팔로우 취소 (본인 팔로우만)
 * - GET    /api/follows/count                특정 유저의 팔로워 수
 * - GET    /api/follows/followed-by-me       특정 유저를 내가 팔로우하는지
 *
 * 명세에는 없지만 기존에 만든 팔로워/팔로잉 목록 조회는 그대로 유지했다.
 */
@RestController
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /** followeeId 를 팔로우하고, 생성된 팔로우 관계를 반환한다. */
    @PostMapping("/api/follows")
    @ResponseStatus(HttpStatus.CREATED)
    public FollowResponse follow(@Valid @RequestBody FollowRequest request) {
        UUID requesterId = SecurityUtil.getCurrentUserId();
        return followService.follow(requesterId, request.followeeId());
    }

    /** 팔로우 취소. 요청자 본인의 팔로우만 취소할 수 있다. */
    @DeleteMapping("/api/follows/{followId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@PathVariable UUID followId) {
        UUID requesterId = SecurityUtil.getCurrentUserId();
        followService.unfollow(requesterId, followId);
    }

    /** 특정 유저의 팔로워 수 */
    @GetMapping("/api/follows/count")
    public long countFollowers(@RequestParam UUID followeeId) {
        return followService.countFollowers(followeeId);
    }

    /** 특정 유저를 내가 팔로우 중이면 팔로우 관계를, 아니면 404 */
    @GetMapping("/api/follows/followed-by-me")
    public FollowResponse followedByMe(@RequestParam UUID followeeId) {
        UUID requesterId = SecurityUtil.getCurrentUserId();
        return followService.getFollowedByMe(requesterId, followeeId);
    }

    /** userId 를 팔로우하는 사람들 목록 */
    @GetMapping("/api/users/{userId}/followers")
    public FollowListResponse getFollowers(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return followService.getFollowers(userId, page, size);
    }

    /** userId 가 팔로우하는 사람들 목록 */
    @GetMapping("/api/users/{userId}/following")
    public FollowListResponse getFollowing(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return followService.getFollowing(userId, page, size);
    }
}