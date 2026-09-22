package com.mopl.review.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record ReviewUpdateRequest(

        @DecimalMin(value = "0.0", message = "평점은 0.0 이상이어야 합니다.")
        @DecimalMax(value = "5.0", message = "평점은 5.0 이하여야 합니다.")
        @Digits(integer = 1, fraction = 1, message = "평점은 소수점 첫째 자리까지 입력할 수 있습니다.")
        BigDecimal rating,

        @Pattern(
                regexp = "(?s).*\\S.*",
                message = "리뷰 내용은 공백일 수 없습니다."
        )
        @Size(max = 1000, message = "리뷰 내용은 1000자를 초과할 수 없습니다")
        String text
) {

        @AssertTrue(message = "수정할 리뷰 정보를 하나 이상 입력해야 합니다.")
        public boolean isUpdateRequested() {
                return rating != null || text != null;
        }
}
