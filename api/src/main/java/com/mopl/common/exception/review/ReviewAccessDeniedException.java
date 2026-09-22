package com.mopl.common.exception.review;

import com.mopl.common.exception.MoplException;

public class ReviewAccessDeniedException extends MoplException {

    public ReviewAccessDeniedException() {
        super(ReviewErrorCode.REVIEW_ACCESS_DENIED);
    }
}
