package com.mopl.auth.controller;

import com.mopl.auth.dto.AuthResponse;
import com.mopl.auth.dto.ResetPasswordRequest;
import com.mopl.auth.jwt.AuthCookieWriter;
import com.mopl.auth.jwt.AuthCookies;
import com.mopl.auth.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인(/sign-in)과 로그아웃(/sign-out)은 Swagger 스펙상
 * "SecurityFilterChain 에서 처리" 하도록 되어 있어 이 컨트롤러가 아니라
 * Spring Security 의 formLogin/logout 내장 필터가 처리한다.
 * (SecurityConfig, EmailPasswordAuthenticationProvider, LoginSuccessHandler/LoginFailureHandler,
 *  LogoutSuccessHandlerImpl 참고)
 * 이 컨트롤러에는 필터로 처리할 수 없는 것들만 남는다: 토큰 재발급, CSRF 토큰 발급, 임시비밀번호 발급.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieWriter cookieWriter;

    @PostMapping("/refresh")
    public AuthResponse refresh(
            @CookieValue(AuthCookies.REFRESH_TOKEN) String refreshToken,
            HttpServletResponse response
    ) {
        AuthService.TokenPair tokenPair = authService.refresh(refreshToken);
        cookieWriter.setAccessTokenCookie(response, tokenPair.accessToken());
        cookieWriter.setRefreshTokenCookie(response, tokenPair.refreshToken());
        return new AuthResponse(tokenPair.user(), tokenPair.accessToken());
    }

    /**
     * CsrfToken 을 파라미터로 받기만 해도 Spring Security 의 지연 토큰 생성(deferred loading)이
     * 실제로 트리거되어 응답에 XSRF-TOKEN 쿠키가 실린다. 프론트가 로그인 전에 먼저 호출해서
     * CSRF 쿠키를 받아가는 용도.
     */
    @GetMapping("/csrf-token")
    public ResponseEntity<Void> csrfToken(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.noContent().build();
    }

    /**
     * 임시 비밀번호로 초기화 후 이메일로 전송한다.
     * TODO: 메일 발송 인프라(SMTP) 미연동 상태라, 실제로는 로그로만 남는다
     * (LoggingTempPasswordMailSender 참고). 인프라 연동 시 구현체만 교체하면 됨.
     */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.email());
    }
}