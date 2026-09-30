package com.mopl.common.exception;

public interface RetryAfterProvider {

	long getRetryAfterSeconds();
}