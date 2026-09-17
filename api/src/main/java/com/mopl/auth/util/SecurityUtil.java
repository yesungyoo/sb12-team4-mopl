package com.mopl.auth.util;

import com.mopl.auth.dto.AuthUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** "현재 로그인한 사용자가 누구인지" 를 어디서든 조회하기 위한 유틸. */
public class SecurityUtil {

    private SecurityUtil() {
    }

    public static AuthUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUser authUser)) {
            throw new IllegalStateException("인증된 사용자 정보를 찾을 수 없습니다.");
        }
        return authUser;
    }

    public static java.util.UUID getCurrentUserId() {
        return getCurrentUser().userId();
    }
}