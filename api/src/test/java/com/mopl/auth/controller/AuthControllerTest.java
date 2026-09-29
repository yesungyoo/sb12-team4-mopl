package com.mopl.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.auth.config.PasswordEncoderConfig;
import com.mopl.auth.config.SecurityConfig;
import com.mopl.auth.handler.LoginFailureHandler;
import com.mopl.auth.handler.LoginSuccessHandler;
import com.mopl.auth.handler.LogoutSuccessHandlerImpl;
import com.mopl.auth.handler.OAuth2LoginHandler;
import com.mopl.auth.jwt.AuthCookieWriter;
import com.mopl.auth.jwt.AuthCookies;
import com.mopl.infrastructure.security.jwt.JwtTokenProvider;
import com.mopl.auth.mail.LoggingTempPasswordMailSender;
import com.mopl.auth.provider.EmailPasswordAuthenticationProvider;
import com.mopl.auth.redis.TokenRedisService;
import com.mopl.auth.service.AuthService;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


/**
 * 이번 PR 에서 새로 만든 인증 핵심 흐름(EmailPasswordAuthenticationProvider, 로그인 성공/실패 핸들러,
 * refresh token rotation, logout 시 Redis 무효화)을 실제로 검증하는 테스트.
 *
 * 외부 시스템(DB, Redis)만 Mock 처리하고, 그 외 인증 로직(비밀번호 검증, JWT 발급, 쿠키 세팅,
 * 응답 조립)은 전부 실제 빈을 그대로 태워서 검증한다 - 그래야 "진짜로 동작하는지"를 확인하는 의미가 있다.
 */
@WebMvcTest(AuthController.class)
@Import({
        SecurityConfig.class,
        PasswordEncoderConfig.class,
        EmailPasswordAuthenticationProvider.class,
        JwtTokenProvider.class,
        AuthCookieWriter.class,
        AuthService.class,
        LoggingTempPasswordMailSender.class,
        LoginSuccessHandler.class,
        LoginFailureHandler.class,
        LogoutSuccessHandlerImpl.class
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TokenRedisService tokenRedisService;

    @MockitoBean
    private OAuth2LoginHandler oAuth2LoginHandler;

    private UUID userId;
    private User activeUser;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        activeUser = new User(
                "test@mopl.com",
                passwordEncoder.encode("password123!"),
                "홍길동",
                null,
                UserRole.USER
        );
        // 실제로 저장된 적 없는 mock 픽스처라 id 가 null 인 상태. LoginSuccessHandler 등에서
        // user.getId() 를 그대로 JWT sub 로 사용하므로, 리플렉션으로 id 를 채워준다.
        org.springframework.test.util.ReflectionTestUtils.setField(activeUser, "id", userId);
    }

    @Nested
    @DisplayName("로그인 (POST /api/auth/sign-in)")
    class SignIn {

        @Test
        @DisplayName("올바른 이메일/비밀번호면 200과 {userDto, accessToken}을 반환하고 refresh token을 Redis에 저장한다")
        void success() throws Exception {
            when(userRepository.findByEmailAndDeletedAtIsNull("test@mopl.com"))
                    .thenReturn(Optional.of(activeUser));
            when(userRepository.findByIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(activeUser));

            mockMvc.perform(post("/api/auth/sign-in")
                            .with(csrf())
                            .param("username", "test@mopl.com")
                            .param("password", "password123!"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userDto.email").value("test@mopl.com"))
                    .andExpect(jsonPath("$.accessToken").exists())
                    .andExpect(cookie().exists(AuthCookies.ACCESS_TOKEN))
                    .andExpect(cookie().exists(AuthCookies.REFRESH_TOKEN));

            verify(tokenRedisService).saveRefreshToken(any(), any());
        }

        @Test
        @DisplayName("비밀번호가 틀리면 401과 InvalidCredentialsException을 반환한다")
        void wrongPassword() throws Exception {
            when(userRepository.findByEmailAndDeletedAtIsNull("test@mopl.com"))
                    .thenReturn(Optional.of(activeUser));

            mockMvc.perform(post("/api/auth/sign-in")
                            .with(csrf())
                            .param("username", "test@mopl.com")
                            .param("password", "wrong-password"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.exceptionName").value("InvalidCredentialsException"));
        }

        @Test
        @DisplayName("존재하지 않는 이메일이면 401과 InvalidCredentialsException을 반환한다 (계정 존재 여부 미노출)")
        void unknownEmail() throws Exception {
            when(userRepository.findByEmailAndDeletedAtIsNull("nobody@mopl.com"))
                    .thenReturn(Optional.empty());

            mockMvc.perform(post("/api/auth/sign-in")
                            .with(csrf())
                            .param("username", "nobody@mopl.com")
                            .param("password", "password123!"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.exceptionName").value("InvalidCredentialsException"));
        }

        @Test
        @DisplayName("잠긴 계정이면 401과 UserLockedException을 반환한다")
        void lockedAccount() throws Exception {
            User locked = new User(
                    "locked@mopl.com",
                    passwordEncoder.encode("password123!"),
                    "잠긴유저",
                    null,
                    UserRole.USER
            );
            // User 엔티티에 잠금 처리용 메서드가 아직 없어서(추후 담당자와 협의 예정 항목) 테스트에서는
            // 리플렉션으로 private 필드를 직접 세팅한다. 엔티티에 메서드가 추가되면 이 부분도 정리 필요.
            org.springframework.test.util.ReflectionTestUtils.setField(locked, "locked", true);
            when(userRepository.findByEmailAndDeletedAtIsNull("locked@mopl.com"))
                    .thenReturn(Optional.of(locked));

            mockMvc.perform(post("/api/auth/sign-in")
                            .with(csrf())
                            .param("username", "locked@mopl.com")
                            .param("password", "password123!"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.exceptionName").value("UserLockedException"));
        }
    }

    @Nested
    @DisplayName("토큰 재발급 (POST /api/auth/refresh)")
    class Refresh {

        @Test
        @DisplayName("유효한 refresh token이면 access/refresh 둘 다 새로 발급하고(rotation) 기존 값을 덮어쓴다")
        void success() throws Exception {
            String oldRefreshToken = jwtTokenProvider.createRefreshToken(userId);
            when(tokenRedisService.isValidRefreshToken(userId, oldRefreshToken)).thenReturn(true);
            when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(activeUser));

            mockMvc.perform(post("/api/auth/refresh")
                            .with(csrf())
                            .cookie(new Cookie(AuthCookies.REFRESH_TOKEN, oldRefreshToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").exists())
                    .andExpect(cookie().exists(AuthCookies.REFRESH_TOKEN));

            ArgumentCaptor<String> newRefreshTokenCaptor = ArgumentCaptor.forClass(String.class);
            verify(tokenRedisService).saveRefreshToken(eq(userId), newRefreshTokenCaptor.capture());
            // rotation 검증: 새로 발급된 refresh token 은 기존 값과 달라야 한다.
            org.assertj.core.api.Assertions.assertThat(newRefreshTokenCaptor.getValue()).isNotEqualTo(oldRefreshToken);
        }

        @Test
        @DisplayName("Redis 에 저장된 값과 다른(이미 무효화된) refresh token 이면 401을 반환한다")
        void invalidatedRefreshToken() throws Exception {
            String staleRefreshToken = jwtTokenProvider.createRefreshToken(userId);
            when(tokenRedisService.isValidRefreshToken(userId, staleRefreshToken)).thenReturn(false);

            mockMvc.perform(post("/api/auth/refresh")
                            .with(csrf())
                            .cookie(new Cookie(AuthCookies.REFRESH_TOKEN, staleRefreshToken)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_001"));
        }
    }

    @Nested
    @DisplayName("로그아웃 (POST /api/auth/sign-out)")
    class SignOut {

        @Test
        @DisplayName("로그아웃하면 Redis 의 refresh token 을 삭제하고 access token 을 무효화 처리한다")
        void success() throws Exception {
            String accessToken = jwtTokenProvider.createAccessToken(userId, "test@mopl.com", UserRole.USER);
            when(tokenRedisService.isInvalidated(eq(userId), any())).thenReturn(false);

            mockMvc.perform(post("/api/auth/sign-out")
                            .with(csrf())
                            .cookie(new Cookie(AuthCookies.ACCESS_TOKEN, accessToken)))
                    .andExpect(status().isNoContent());

            verify(tokenRedisService).deleteRefreshToken(userId);
            verify(tokenRedisService).invalidateTokensIssuedBefore(eq(userId), any());
        }
    }
}