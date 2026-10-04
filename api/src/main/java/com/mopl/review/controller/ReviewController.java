package com.mopl.review.controller;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewResponse;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.service.ReviewService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public ResponseEntity<CursorResponse<ReviewResponse>> getReviews(
            @RequestParam(required = false) UUID contentId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam int limit,
            @RequestParam String sortDirection,
            @RequestParam String sortBy
    ) {
        CursorResponse<ReviewResponse> response = reviewService.getReviews(
                contentId,
                cursor,
                idAfter,
                limit,
                sortBy,
                sortDirection
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<ReviewResponse> createReview(
            @Valid @RequestBody ReviewCreateRequest request
    ) {
        UUID userId = SecurityUtil.getCurrentUserId();

        ReviewResponse response = reviewService.createReview(
                userId,
                request
        );

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{reviewId}")
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

    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> deleteReview(@PathVariable UUID reviewId) {
        UUID userId = SecurityUtil.getCurrentUserId();

        reviewService.deleteReview(
                userId,
                reviewId
        );

        return ResponseEntity.noContent().build();
    }
}
