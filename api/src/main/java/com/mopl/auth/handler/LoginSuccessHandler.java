package com.mopl.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.auth.dto.AuthResponse;
import com.mopl.auth.dto.AuthUser;
import com.mopl.auth.jwt.AuthCookieWriter;
import com.mopl.auth.jwt.JwtTokenProvider;
import com.mopl.auth.redis.TokenRedisService;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.dto.UserResponse;
import com.mopl.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenRedisService tokenRedisService;
    private final UserRepository userRepository;
    private final AuthCookieWriter cookieWriter;
    private final ObjectMapper objectMapper;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication
    ) throws IOException {
        AuthUser authUser = (AuthUser) authentication.getPrincipal();
        User user = userRepository.findByIdAndDeletedAtIsNull(authUser.userId())
                .orElseThrow(() -> new IllegalStateException("인증 직후 사용자를 찾을 수 없습니다. userId=" + authUser.userId()));

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());
        tokenRedisService.saveRefreshToken(user.getId(), refreshToken);

        cookieWriter.setAccessTokenCookie(response, accessToken);
        cookieWriter.setRefreshTokenCookie(response, refreshToken);

        AuthResponse body = new AuthResponse(UserResponse.from(user), accessToken);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}