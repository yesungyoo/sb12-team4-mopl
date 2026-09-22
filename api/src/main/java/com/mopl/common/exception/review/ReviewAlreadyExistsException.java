package com.mopl.common.exception.review;

import com.mopl.common.exception.MoplException;

public class ReviewAlreadyExistsException extends MoplException {

    public ReviewAlreadyExistsException() {
        super(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
    }
}
