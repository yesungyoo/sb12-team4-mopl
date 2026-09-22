package com.mopl.auth.service;

import com.mopl.auth.jwt.JwtTokenProvider;
import com.mopl.auth.mail.TempPasswordMailSender;
import com.mopl.auth.redis.TokenRedisService;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.auth.AuthErrorCode;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.dto.UserResponse;
import com.mopl.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인/로그아웃은 Swagger 스펙상 Spring Security formLogin/logout(내장 필터)이 처리하므로
 * (EmailPasswordAuthenticationProvider, LoginSuccessHandler, LogoutSuccessHandlerImpl 참고),
 * 이 서비스에는 컨트롤러가 직접 호출하는 refresh(토큰 재발급)와 reset-password(임시 비밀번호 발급)가 남아있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    /** 자료에 만료 시간이 명시되어 있지 않아 임의로 정함. 프론트/기획과 합의 후 조정 필요. */
    private static final long TEMP_PASSWORD_VALID_MINUTES = 10;
    private static final int TEMP_PASSWORD_LENGTH = 10;
    private static final String TEMP_PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%";

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenRedisService tokenRedisService;
    private final PasswordEncoder passwordEncoder;
    private final TempPasswordMailSender tempPasswordMailSender;
    private final SecureRandom secureRandom = new SecureRandom();

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

    /**
     * 임시 비밀번호를 생성해 암호화 후 저장하고(만료시간 포함), 이메일로 발송한다.
     * Swagger 스펙상 404(해당 리소스 없음)가 정의되어 있어, 이메일이 존재하지 않으면 404 로 응답한다.
     * (참고: 이 방식은 "가입된 이메일인지" 를 외부에 노출하게 되어 일반적인 보안 권고와는 다르지만,
     *  스펙에 404 응답이 명시되어 있어 그대로 따름 - 계정 존재 노출을 원치 않으면 추후 스펙 변경 필요)
     */
    @Transactional
    public void resetPassword(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new MoplException(UserErrorCode.USER_NOT_FOUND, "존재하지 않는 이메일입니다. email=" + email));

        String tempPassword = generateTempPassword();
        String encodedTempPassword = passwordEncoder.encode(tempPassword);
        LocalDateTime expiredAt = LocalDateTime.now().plusMinutes(TEMP_PASSWORD_VALID_MINUTES);

        userRepository.updateTempPassword(user.getId(), encodedTempPassword, expiredAt);

        // 암호화 전 원문(tempPassword)을 메일로만 보내고 어디에도 평문으로 저장하지 않는다.
        tempPasswordMailSender.send(user.getEmail(), tempPassword);
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_CHARS.charAt(secureRandom.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}