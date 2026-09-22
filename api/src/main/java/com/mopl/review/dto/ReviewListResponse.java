package com.mopl.review.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record ReviewListResponse(
        List<ReviewResponse> reviews,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static ReviewListResponse from(Page<ReviewResponse> reviewPage) {
        return new ReviewListResponse(
                reviewPage.getContent(),
                reviewPage.getNumber(),
                reviewPage.getSize(),
                reviewPage.getTotalElements(),
                reviewPage.getTotalPages()
        );
    }
}
