package com.mopl.batch.external.sportsdb.exception;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class SportsDbApiException extends RuntimeException {

    private final HttpStatusCode statusCode;

    public SportsDbApiException(HttpStatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
