package com.mopl.content.dto;

import com.mopl.core.common.enums.ContentType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ContentUpdateRequest(

        ContentType type,

        @Pattern(regexp = ".*\\S.*", message = "콘텐츠 제목은 공백일 수 없습니다.")
        @Size(max = 255, message = "콘텐츠 제목은 255자를 초과할 수 없습니다.")
        String title,

        String description,

        @Size(max = 1000, message = "썸네일 URL은 1000자를 초과할 수 없습니다.")
        String thumbnailUrl,

        LocalDate releaseDate
) {

        @AssertTrue(message = "수정할 콘텐츠 정보를 하나 이상 입력해야 합니다.")
        public boolean isUpdateRequested() {
                return type != null
                        || title != null
                        || description != null
                        || thumbnailUrl != null
                        || releaseDate != null;
        }
}
