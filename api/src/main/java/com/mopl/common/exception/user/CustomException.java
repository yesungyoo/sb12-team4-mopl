package com.mopl.common.exception.user;

import java.util.Collections;
import java.util.Map;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 공용 예외 베이스. { exceptionName, message, details } 응답 스펙과 매칭됩니다.
 * NOTE: 이미 api 모듈에 동일한 역할의 베이스 클래스가 있다면 이 파일은 지우고 그걸 상속하도록
 * UserNotFoundException 등의 import 만 바꿔주세요.
 */
@Getter
public abstract class CustomException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, String> details;

    protected CustomException(HttpStatus status, String message) {
        this(status, message, Collections.emptyMap());
    }

    protected CustomException(HttpStatus status, String message, Map<String, String> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public String getExceptionName() {
        return this.getClass().getSimpleName();
    }
}
