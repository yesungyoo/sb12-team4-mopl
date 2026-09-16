package com.mopl.common.exception.notification;

import com.mopl.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    NOTIFICATION_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "NOTIFICATION_001",
        "알림을 찾을 수 없습니다."
    ),
    USER_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "NOTIFICATION_002",
        "사용자를 찾을 수 없습니다."
    ),
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;
}