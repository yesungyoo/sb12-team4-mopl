package com.mopl.realtime.moderation.exception;

public class InvalidMessageReviewResponseException extends RuntimeException {
    public InvalidMessageReviewResponseException() { super("Untrusted message review response"); }
}
