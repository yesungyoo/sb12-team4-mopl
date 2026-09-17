package com.mopl.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception
    ) throws IOException {
        String exceptionName;
        String message;

        if (exception instanceof LockedException) {
            exceptionName = "UserLockedException";
            message = "잠긴 계정입니다. 관리자에게 문의해주세요.";
        } else {
            // 이메일 존재 여부를 노출하지 않기 위해 계정 없음/비밀번호 틀림을 구분하지 않고 동일하게 응답
            exceptionName = "InvalidCredentialsException";
            message = "이메일 또는 비밀번호가 올바르지 않습니다.";
        }

        Map<String, Object> body = Map.of(
                "exceptionName", exceptionName,
                "message", message,
                "details", Map.of()
        );

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}