package com.mopl.core.common.dto;

import java.util.List;

public record CursorResponse<T>(
    List<T> data,
    String nextCursor,
    String nextIdAfter,
    boolean hasNext,
    long totalCount,
    String sortBy,
    String sortDirection
) {
  public static <T> CursorResponse<T> of(
      List<T> data,
      String nextCursor,
      String nextIdAfter,
      boolean hasNext,
      long totalCount,
      String sortBy,
      String sortDirection
  ) {
    return new CursorResponse<>(data, nextCursor, nextIdAfter, hasNext, totalCount, sortBy, sortDirection);
  }
}