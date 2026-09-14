package com.mopl.playlist.dto;

import jakarta.validation.constraints.Size;

public record PlaylistUpdateRequest(
	@Size(max = 100, message = "제목은 100자를 초과할 수 없습니다.")
	String title,

	@Size(max = 500, message = "설명은 500자를 초과할 수 없습니다.")
	String description
) {
}