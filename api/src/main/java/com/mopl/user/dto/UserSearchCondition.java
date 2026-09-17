package com.mopl.user.dto;

import com.mopl.core.common.enums.UserRole;

/**
 * GET /api/users 쿼리 파라미터.
 * cursor: 정렬 기준 컬럼 값(문자열 직렬화), idAfter: 동점자 처리를 위한 보조 커서(id, UUID 문자열)
 */
public record UserSearchCondition(
        String emailLike,
        UserRole roleEqual,
        Boolean isLocked,
        String cursor,
        String idAfter,
        int limit,
        SortDirection sortDirection,
        String sortBy
) {
    public enum SortDirection {
        ASCENDING, DESCENDING
    }
}
