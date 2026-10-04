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
        // 프론트(S3)와 API(CloudFront)가 서로 다른 site라 cross-site 쿠키 전송이 필요함.
        // SameSite=None은 반드시 Secure와 함께 써야 브라우저가 쿠키를 저장/전송한다.
        ResponseCookie cookie = ResponseCookie.from(AuthCookies.ACCESS_TOKEN, accessToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(jwtTokenProvider.getAccessTokenExpirationSeconds())
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(AuthCookies.REFRESH_TOKEN, refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/api/auth")
                .maxAge(jwtTokenProvider.getRefreshTokenExpirationSeconds())
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clearAuthCookies(HttpServletResponse response) {
        ResponseCookie accessCookie = ResponseCookie.from(AuthCookies.ACCESS_TOKEN, "")
                .httpOnly(true).secure(true).sameSite("None").path("/").maxAge(0).build();
        ResponseCookie refreshCookie = ResponseCookie.from(AuthCookies.REFRESH_TOKEN, "")
                .httpOnly(true).secure(true).sameSite("None").path("/api/auth").maxAge(0).build();
        response.addHeader("Set-Cookie", accessCookie.toString());
        response.addHeader("Set-Cookie", refreshCookie.toString());
    }
}
