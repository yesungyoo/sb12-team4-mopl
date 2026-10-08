package com.mopl.common.exception.content;

import com.mopl.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ContentErrorCode implements ErrorCode {

     CONTENT_NOT_FOUND(
             HttpStatus.NOT_FOUND,
             "CONTENT_001",
             "콘텐츠를 찾을 수 없습니다."
     ),
     SEMANTIC_SEARCH_UNAVAILABLE(
             HttpStatus.SERVICE_UNAVAILABLE,
             "CONTENT_002",
             "시맨틱 검색을 일시적으로 사용할 수 없습니다."
     );

    private final HttpStatus status;
    private final String code;
    private final String message;

}
