package com.mopl.common.exception.auth;

import com.mopl.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "AUTH_001",
            "이메일 또는 비밀번호가 올바르지 않습니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;
}