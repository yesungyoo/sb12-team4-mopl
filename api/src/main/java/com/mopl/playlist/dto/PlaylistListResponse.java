package com.mopl.playlist.dto;

import java.util.List;
import java.util.UUID;

public record PlaylistListResponse(
	List<PlaylistResponse> data,
	String nextCursor,
	UUID nextIdAfter,
	boolean hasNext,
	String sortBy,
	String sortDirection,
	long totalCount
) {
}