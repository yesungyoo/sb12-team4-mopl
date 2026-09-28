package com.mopl.auth.jwt;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.mopl.infrastructure.security.jwt.JwtTokenProvider;

/**
 * 로그인 성공 시(LoginSuccessHandler)와 토큰 재발급 시(AuthController.refresh) 둘 다
 * 동일한 방식으로 쿠키를 세팅해야 해서 공통 컴포넌트로 분리.
 */
@Component
@RequiredArgsConstructor
public class AuthCookieWriter {

    private final JwtTokenProvider jwtTokenProvider;

    public void setAccessTokenCookie(HttpServletResponse response, String accessToken) {
        ResponseCookie cookie = ResponseCookie.from(AuthCookies.ACCESS_TOKEN, accessToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(jwtTokenProvider.getAccessTokenExpirationSeconds())
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(AuthCookies.REFRESH_TOKEN, refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(jwtTokenProvider.getRefreshTokenExpirationSeconds())
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clearAuthCookies(HttpServletResponse response) {
        ResponseCookie accessCookie = ResponseCookie.from(AuthCookies.ACCESS_TOKEN, "")
                .httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(0).build();
        ResponseCookie refreshCookie = ResponseCookie.from(AuthCookies.REFRESH_TOKEN, "")
                .httpOnly(true).secure(true).sameSite("Strict").path("/api/auth").maxAge(0).build();
        response.addHeader("Set-Cookie", accessCookie.toString());
        response.addHeader("Set-Cookie", refreshCookie.toString());
    }
}