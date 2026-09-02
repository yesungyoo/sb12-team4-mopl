package com.mopl.common.exception;

import lombok.Getter;

@Getter
public class MoplException extends RuntimeException{

    private final ErrorCode errorCode;

    public MoplException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public MoplException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
