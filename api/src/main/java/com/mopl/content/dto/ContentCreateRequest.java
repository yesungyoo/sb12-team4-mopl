package com.mopl.content.dto;

import com.mopl.core.common.enums.ContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ContentCreateRequest(

        @NotNull(message = "콘텐츠 타입은 필수입니다.")
        ContentType type,

        @NotBlank(message = "콘텐츠 제목은 필수입니다.")
        @Size(max = 255, message = "콘텐츠 제목은 255자를 초과할 수 없습니다.")
        String title,

        String description,

        @Size(max = 1000, message = "썸네일 URL은 1000자를 초과할 수 없습니다.")
        String thumbnailUrl,

        LocalDate releaseDate
) {
}
