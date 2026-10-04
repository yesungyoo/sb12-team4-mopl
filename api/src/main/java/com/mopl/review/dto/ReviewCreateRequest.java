package com.mopl.review.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record ReviewCreateRequest(

        @NotNull(message = "콘텐츠 ID는 필수입니다.")
        UUID contentId,

        @NotBlank(message = "리뷰 내용은 필수입니다.")
        @Size(max = 1000, message = "리뷰 내용은 1000자를 초과할 수 없습니다.")
        String text,

        @NotNull(message = "평점은 필수입니다.")
        @DecimalMin(value = "0.0", message = "평점은 0.0 이상이어야 합니다.")
        @DecimalMax(value = "5.0", message = "평점은 5.0 이하여야 합니다.")
        @Digits(integer = 1, fraction = 1, message = "평점은 소수점 첫째 자리까지만 입력 할 수 있습니다.")
        BigDecimal rating
) {
}
