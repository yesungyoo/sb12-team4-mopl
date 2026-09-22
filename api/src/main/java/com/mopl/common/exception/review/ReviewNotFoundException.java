package com.mopl.common.exception.review;

import com.mopl.common.exception.MoplException;

public class ReviewNotFoundException extends MoplException {

    public ReviewNotFoundException() {
        super(ReviewErrorCode.REVIEW_NOT_FOUND);
    }
}
