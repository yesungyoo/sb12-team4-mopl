package com.mopl.user.controller;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.user.dto.FollowListResponse;
import com.mopl.user.service.FollowService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 경로/응답 형태는 자료에 팔로우 전용 스펙이 없어 관례대로 설계했습니다.
 * 실제 스펙이 확정되면 맞춰주세요.
 */
@RestController
@RequestMapping("/api/users/{userId}")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /** userId 를 팔로우 */
    @PostMapping("/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void follow(@PathVariable UUID userId) {
        UUID requesterId = SecurityUtil.getCurrentUserId();
        followService.follow(requesterId, userId);
    }

    /** userId 팔로우 취소 */
    @DeleteMapping("/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@PathVariable UUID userId) {
        UUID requesterId = SecurityUtil.getCurrentUserId();
        followService.unfollow(requesterId, userId);
    }

    /** userId 를 팔로우하는 사람들 목록 */
    @GetMapping("/followers")
    public FollowListResponse getFollowers(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return followService.getFollowers(userId, page, size);
    }

    /** userId 가 팔로우하는 사람들 목록 */
    @GetMapping("/following")
    public FollowListResponse getFollowing(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return followService.getFollowing(userId, page, size);
    }
}