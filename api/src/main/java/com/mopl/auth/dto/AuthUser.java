package com.mopl.auth.dto;

import com.mopl.core.common.enums.UserRole;
import java.util.UUID;

/**
 * 인증된 요청의 "현재 로그인 사용자" 정보.
 * JwtAuthenticationFilter 가 토큰 claim 을 그대로 담아 SecurityContext 의 principal 로 사용한다.
 * (매 요청마다 DB 를 다시 조회하지 않기 위함 - 잠금/권한변경/탈퇴 반영은 Redis 무효화 체크로 처리)
 */
public record AuthUser(
        UUID userId,
        String email,
        UserRole role
) {
}
