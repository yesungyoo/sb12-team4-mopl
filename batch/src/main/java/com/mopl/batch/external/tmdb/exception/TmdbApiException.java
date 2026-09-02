package com.mopl.batch.external.tmdb.exception;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class TmdbApiException extends RuntimeException {

    private final HttpStatusCode statusCode;

    public TmdbApiException(HttpStatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
