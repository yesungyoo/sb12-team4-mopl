package com.mopl.playlist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlaylistCreateRequest(
	@NotBlank(message = "제목은 필수입니다.")
	@Size(max = 100, message = "제목은 100자를 초과할 수 없습니다.")
	String title,

	@NotBlank(message = "설명은 필수입니다.")
	@Size(max = 500, message = "설명은 500자를 초과할 수 없습니다.")
	String description
) {
}