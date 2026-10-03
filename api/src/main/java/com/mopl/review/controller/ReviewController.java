package com.mopl.review.controller;

import static org.springframework.data.domain.Sort.Direction.DESC;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewListResponse;
import com.mopl.review.dto.ReviewResponse;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.service.ReviewService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/reviews/{reviewId}")
    public ResponseEntity<ReviewResponse> getReview(@PathVariable UUID reviewId) {
        ReviewResponse response = reviewService.getReview(reviewId);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/contents/{contentId}/reviews")
    public ResponseEntity<ReviewListResponse> getReviews(
            @PathVariable UUID contentId,
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable
    ) {
        ReviewListResponse response = reviewService.getReviews(contentId, pageable);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/contents/{contentId}/reviews")
    public ResponseEntity<ReviewResponse> createReview(
            @PathVariable UUID contentId,
            @Valid @RequestBody ReviewCreateRequest request
    ) {
        UUID userId = SecurityUtil.getCurrentUserId();

        ReviewResponse response = reviewService.createReview(
                userId,
                contentId,
                request
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PatchMapping("/reviews/{reviewId}")
    public ResponseEntity<ReviewResponse> updateReview(
            @PathVariable UUID reviewId,
            @Valid @RequestBody ReviewUpdateRequest request
    ) {
        UUID userId = SecurityUtil.getCurrentUserId();

        ReviewResponse response = reviewService.updateReview(
                userId,
                reviewId,
                request
        );

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<Void> deleteReview(@PathVariable UUID reviewId) {
        UUID userId = SecurityUtil.getCurrentUserId();

        reviewService.deleteReview(
                userId,
                reviewId
        );

        return ResponseEntity.noContent().build();
    }
}
