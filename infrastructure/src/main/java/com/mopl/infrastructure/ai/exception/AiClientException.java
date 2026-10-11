package com.mopl.infrastructure.ai.exception;

public class AiClientException extends RuntimeException {

    private final Integer httpStatusCode;

    public AiClientException(String message) {
        super(message);
        this.httpStatusCode = null;
    }

    public AiClientException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = null;
    }

    public AiClientException(
            String message,
            int httpStatusCode,
            Throwable cause
    ) {
        super(message, cause);
        this.httpStatusCode = httpStatusCode;
    }

    public Integer httpStatusCode() {
        return httpStatusCode;
    }
}
