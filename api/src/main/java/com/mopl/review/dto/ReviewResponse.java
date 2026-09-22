package com.mopl.review.dto;

import com.mopl.core.domain.review.entity.Review;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ReviewResponse(
        UUID id,
        UUID contentId,
        ReviewAuthorResponse author,
        BigDecimal rating,
        String text,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getContent().getId(),
                ReviewAuthorResponse.from(review.getUser()),
                review.getRating(),
                review.getText(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
