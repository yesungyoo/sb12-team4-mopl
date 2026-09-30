package com.mopl.auth.social;

import com.mopl.core.common.enums.SocialProvider;

/**
 * 소셜 로그인 제공자(구글/카카오)마다 다른 응답 구조를 우리 서비스가 쓰는 형태로 통일한 값.
 * emailVerified 가 false 면 그 이메일로는 가입/판단하지 않는다.
 */
public record SocialUserInfo(
        SocialProvider provider,
        String providerId,
        String email,
        boolean emailVerified,
        String name,
        String pictureUrl
) {
}