package com.mopl.auth.handler;

import com.mopl.auth.dto.AuthUser;
import com.mopl.auth.jwt.AuthCookieWriter;
import com.mopl.auth.redis.TokenRedisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LogoutSuccessHandlerImpl implements LogoutSuccessHandler {

    private final TokenRedisService tokenRedisService;
    private final AuthCookieWriter cookieWriter;

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        // JwtAuthenticationFilter 가 LogoutFilter 보다 먼저 실행되도록 SecurityConfig 에서 순서를 맞춰뒀기 때문에
        // 여기 도달했을 때는 이미 SecurityContext 에 AuthUser 가 세팅되어 있다.
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser authUser) {
            tokenRedisService.deleteRefreshToken(authUser.userId());
            tokenRedisService.invalidateTokensIssuedBefore(authUser.userId(), Instant.now());
        }
        cookieWriter.clearAuthCookies(response);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}