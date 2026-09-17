package com.mopl.auth.service;

import com.mopl.auth.jwt.JwtTokenProvider;
import com.mopl.auth.redis.TokenRedisService;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.auth.AuthErrorCode;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.dto.UserResponse;
import com.mopl.user.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인/로그아웃은 Swagger 스펙상 Spring Security formLogin/logout(내장 필터)이 처리하므로
 * (EmailPasswordAuthenticationProvider, LoginSuccessHandler, LogoutSuccessHandlerImpl 참고),
 * 이 서비스에는 컨트롤러가 직접 호출하는 refresh(토큰 재발급)만 남아있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenRedisService tokenRedisService;

    public record TokenPair(String accessToken, String refreshToken, UserResponse user) {
    }

    /**
     * refresh token 을 검증한 뒤, access token 과 refresh token 을 둘 다 재발급한다(rotation).
     * Swagger 스펙: refresh token 도 함께 재발급되어야 하고, 기존 발급 방식(access token만 재발급)은 스펙과 달랐음.
     */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || !jwtTokenProvider.isValid(refreshToken)) {
            throw new MoplException(AuthErrorCode.INVALID_CREDENTIALS, "유효하지 않은 refresh token 입니다.");
        }

        UUID userId = jwtTokenProvider.getUserId(jwtTokenProvider.parseClaims(refreshToken));

        if (!tokenRedisService.isValidRefreshToken(userId, refreshToken)) {
            // Redis 에 저장된 최신 refresh token 과 다르면(이미 재발급되었거나 로그아웃된 경우) 거부
            throw new MoplException(AuthErrorCode.INVALID_CREDENTIALS, "이미 사용되었거나 무효화된 refresh token 입니다.");
        }

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new MoplException(AuthErrorCode.INVALID_CREDENTIALS, "존재하지 않는 사용자입니다."));

        if (user.isLocked()) {
            throw new MoplException(UserErrorCode.USER_LOCKED);
        }

        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        String newRefreshToken = jwtTokenProvider.createRefreshToken(user.getId());
        tokenRedisService.saveRefreshToken(user.getId(), newRefreshToken); // rotation: 기존 값을 새 값으로 덮어씀

        return new TokenPair(newAccessToken, newRefreshToken, UserResponse.from(user));
    }
}