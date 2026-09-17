package com.mopl.user.dto;

import java.util.List;

/**
 * 페이지 기반 목록 응답. (User 조회 때 쓴 커서 방식과 다르게, content/playlist 쪽 관례인
 * page/size/totalElements 패턴을 따름 - 자료에 팔로우 전용 스펙이 없어 기존 관례 중 더 흔한 쪽을 채택)
 */
public record FollowListResponse(
        List<UserResponse> data,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {
}