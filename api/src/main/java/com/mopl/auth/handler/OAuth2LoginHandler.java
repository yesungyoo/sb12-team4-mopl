package com.mopl.auth.handler;

import com.mopl.auth.exception.SocialLoginException;
import com.mopl.auth.jwt.AuthCookieWriter;
import com.mopl.auth.redis.TokenRedisService;
import com.mopl.auth.service.SocialAuthService;
import com.mopl.auth.social.SocialUserInfo;
import com.mopl.auth.social.SocialUserInfoMapper;
import com.mopl.infrastructure.security.jwt.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인(구글/카카오) 성공/실패 처리.
 *
 * 일반 로그인(LoginSuccessHandler)은 JSON 으로 응답하지만, 소셜 로그인은 제공자에서 브라우저가 우리 콜백 주소로
 * 직접 리다이렉트되어 돌아오는 흐름이라 JSON 응답을 받아줄 곳이 없다. 그래서 성공하면 JWT 쿠키를 심고
 * 프론트 주소로 리다이렉트한다.
 *
 * 실패 시에는 요구사항대로 로그인 화면으로 리다이렉트하고 쿼리 파라미터(error, error_message)에 사유를 담는다.
 * 프론트가 해시 라우팅(#/sign-in)이라 쿼리스트링이 "#" 뒤에 와야 한다.
 *   예) http://localhost:8080/#/sign-in?error=oauth_failed&error_message=email_already_registered
 *
 * 제공자별 응답 구조 차이는 SocialUserInfoMapper 가 흡수한다.
 *
 * app.oauth2.frontend-redirect-url (환경변수 APP_OAUTH2_FRONTEND_REDIRECT_URL) 은 프론트가 서비스되는
 * 주소의 루트(예: http://localhost:8080/)를 넣는다. 기본값 "/" 는 프론트가 같은 서버에서 서비스될 때의 값.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final String ERROR_CODE = "oauth_failed";

    private final SocialAuthService socialAuthService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenRedisService tokenRedisService;
    private final AuthCookieWriter cookieWriter;

    @Value("${app.oauth2.frontend-redirect-url:/}")
    private String frontendRedirectUrl;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication
    ) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            redirectWithError(response, "login_failed");
            return;
        }

        SocialUserInfo info;
        try {
            info = SocialUserInfoMapper.map(token.getAuthorizedClientRegistrationId(), token.getPrincipal().getAttributes());
        } catch (IllegalArgumentException e) {
            log.warn("소셜 로그인 사용자 정보 변환 실패: {}", e.getMessage());
            redirectWithError(response, "login_failed");
            return;
        }

        try {
            SocialAuthService.SocialLoginResult result = socialAuthService.loginOrRegister(
                    info.provider(), info.providerId(), info.email(), info.emailVerified(), info.name(), info.pictureUrl()
            );

            String accessToken = jwtTokenProvider.createAccessToken(result.userId(), result.email(), result.role());
            String refreshToken = jwtTokenProvider.createRefreshToken(result.userId());
            tokenRedisService.saveRefreshToken(result.userId(), refreshToken);
            cookieWriter.setAccessTokenCookie(response, accessToken);
            cookieWriter.setRefreshTokenCookie(response, refreshToken);

            response.sendRedirect(frontendRedirectUrl);
        } catch (SocialLoginException e) {
            log.info("소셜 로그인 거절: {}", e.getErrorCode().getCode());
            redirectWithError(response, toErrorMessage(e));
        }
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception
    ) throws IOException {
        log.warn("소셜 로그인 실패: {}", exception.getMessage());
        // 제공자 화면에서 취소한 경우 등 OAuth2 표준 에러 코드(access_denied 등)가 있으면 그대로 전달
        String message = (exception instanceof OAuth2AuthenticationException oauth2Exception)
                ? oauth2Exception.getError().getErrorCode()
                : "login_failed";
        redirectWithError(response, message);
    }

    private String toErrorMessage(SocialLoginException e) {
        return switch (e.getErrorCode()) {
            case USER_LOCKED -> "user_locked";
            case USER_WITHDRAWN -> "user_withdrawn";
            case SOCIAL_EMAIL_ALREADY_REGISTERED -> "email_already_registered";
            case SOCIAL_EMAIL_NOT_VERIFIED -> "email_not_verified";
            default -> "login_failed";
        };
    }

    private void redirectWithError(HttpServletResponse response, String errorMessage) throws IOException {
        String base = frontendRedirectUrl.endsWith("/") ? frontendRedirectUrl : frontendRedirectUrl + "/";
        response.sendRedirect(base + "#/sign-in?error=" + ERROR_CODE
                + "&error_message=" + URLEncoder.encode(errorMessage, StandardCharsets.UTF_8));
    }
}