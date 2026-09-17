package com.mopl.user.dto;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> data,
        String nextCursor,
        String nextIdAfter,
        boolean hasNext,
        long totalCount,
        String sortBy,
        String sortDirection
) {
}