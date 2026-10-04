package com.mopl.review.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record ReviewListResponse(
        List<ReviewPageItemResponse> reviews,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static ReviewListResponse from(Page<ReviewPageItemResponse> reviewPage) {
        return new ReviewListResponse(
                reviewPage.getContent(),
                reviewPage.getNumber(),
                reviewPage.getSize(),
                reviewPage.getTotalElements(),
                reviewPage.getTotalPages()
        );
    }
}
