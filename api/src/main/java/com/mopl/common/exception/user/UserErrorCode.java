package com.mopl.common.exception.user;

import com.mopl.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    USER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "USER_001",
            "존재하지 않는 사용자입니다."
    ),
    USER_LOCKED(
            HttpStatus.UNAUTHORIZED,
            "USER_002",
            "잠긴 계정입니다. 관리자에게 문의해주세요."
    ),
    USER_WITHDRAWN(
            HttpStatus.UNAUTHORIZED,
            "USER_003",
            "탈퇴한 계정입니다."
    ),
    ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "USER_004",
            "본인의 정보만 수정할 수 있습니다."
    ),
    INVALID_SORT_FIELD(
            HttpStatus.BAD_REQUEST,
            "USER_005",
            "지원하지 않는 정렬 기준입니다."
    ),
    EMAIL_ALREADY_EXISTS(
            HttpStatus.BAD_REQUEST,
            "USER_006",
            "이미 사용 중인 이메일입니다."
    ),
    SELF_FOLLOW_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST,
            "USER_007",
            "자기 자신은 팔로우할 수 없습니다."
    ),
    ALREADY_FOLLOWING(
            HttpStatus.BAD_REQUEST,
            "USER_008",
            "이미 팔로우한 사용자입니다."
    ),
    FOLLOW_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "USER_009",
            "팔로우 관계를 찾을 수 없습니다."
    ),
    INVALID_PAGINATION_PARAM(
            HttpStatus.BAD_REQUEST,
            "USER_010",
            "잘못된 페이지네이션 파라미터입니다."
    ),
    INVALID_IMAGE_FILE(
            HttpStatus.BAD_REQUEST,
            "USER_011",
            "지원하지 않는 이미지 파일입니다."
    ),

    SOCIAL_EMAIL_ALREADY_REGISTERED(
            HttpStatus.CONFLICT,
            "USER_012",
            "이미 이메일로 가입된 계정입니다. 이메일 로그인을 이용해주세요."
    ),
    SOCIAL_EMAIL_NOT_VERIFIED(
            HttpStatus.BAD_REQUEST,
            "USER_013",
            "이메일 인증이 확인되지 않은 소셜 계정입니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;
}