package com.mopl.common.exception.review;

import com.mopl.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReviewErrorCode implements ErrorCode {

    REVIEW_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "REVIEW_001",
            "리뷰를 찾을 수 없습니다."
    ),

    REVIEW_ALREADY_EXISTS(
            HttpStatus.CONFLICT,
            "REVIEW_002",
            "이미 해당 콘텐츠에 리뷰를 작성했습니다."
    ),

    REVIEW_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "REVIEW_003",
            "리뷰를 수정하거나 삭제할 권한이 없습니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;
}