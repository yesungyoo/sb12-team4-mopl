package com.mopl.auth.exception;

import com.mopl.common.exception.user.UserErrorCode;

/**
 * 소셜 로그인 처리 중 발생하는 실패 사유. OAuth2 콜백은 필터 체인 안에서 처리되어
 * @RestControllerAdvice 가 잡아주지 못하므로, 핸들러(OAuth2LoginHandler)가 직접 catch 해서
 * 에러 코드를 프론트로 리다이렉트할 때 쓴다.
 */
public class SocialLoginException extends RuntimeException {

    private final UserErrorCode errorCode;

    public SocialLoginException(UserErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public UserErrorCode getErrorCode() {
        return errorCode;
    }
}