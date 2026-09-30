package com.mopl.review.service;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.review.ReviewAccessDeniedException;
import com.mopl.common.exception.review.ReviewAlreadyExistsException;
import com.mopl.common.exception.review.ReviewNotFoundException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.event.ContentSearchStatisticsSyncEvent;
import com.mopl.core.common.event.FollowingReviewCreatedEvent;
import com.mopl.core.common.event.RecommendationPreferenceChangedEvent;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.review.entity.Review;
import com.mopl.core.domain.user.entity.User;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewListResponse;
import com.mopl.review.dto.ReviewResponse;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.repository.ReviewRepository;
import com.mopl.user.repository.FollowRepository;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final ApplicationEventPublisher eventPublisher;

    // 리뷰 단건 조회
    public ReviewResponse getReview(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(ReviewNotFoundException::new);

        return ReviewResponse.from(review);
    }

    // 리뷰 목록 조회
    public ReviewListResponse getReviews(UUID contentId, Pageable pageable) {
        if (!contentRepository.existsByIdAndDeletedAtIsNull(contentId)) {
            throw new ContentNotFoundException();
        }

        Page<ReviewResponse> reviewPage = reviewRepository
                .findAllByContentId(contentId, pageable)
                .map(ReviewResponse::from);

        return ReviewListResponse.from(reviewPage);
    }

    // 리뷰 생성
    @Transactional
    public ReviewResponse createReview(UUID userId, UUID contentId, ReviewCreateRequest request) {
        User user = findActiveUser(userId);

        Content content = contentRepository
                .findByIdAndDeletedAtIsNull(contentId)
                .orElseThrow(ContentNotFoundException::new);

        if (reviewRepository.existsByUserIdAndContentId(userId, contentId)) {
            throw new ReviewAlreadyExistsException();
        }

        Review review = new Review(
                user,
                content,
                request.rating(),
                request.text()
        );

        Review savedReview = reviewRepository.save(review);

        publishReviewCreatedEvent(user, content);

        publishContentSearchStatisticsSyncEvent(contentId);

        publishRecommendationPreferenceChangedEvent(userId);

        return ReviewResponse.from(savedReview);
    }

    // 리뷰 수정
    @Transactional
    public ReviewResponse updateReview(UUID userId, UUID reviewId, ReviewUpdateRequest request) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(ReviewNotFoundException::new);

        validateAuthor(review, userId);

        review.update(
                request.rating(),
                request.text()
        );

        publishContentSearchStatisticsSyncEvent(
                review.getContent().getId()
        );

        publishRecommendationPreferenceChangedEvent(userId);

        return ReviewResponse.from(review);
    }

    // 리뷰 삭제
    @Transactional
    public void deleteReview(UUID userId, UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(ReviewNotFoundException::new);

        validateAuthor(review, userId);

        UUID contentId = review.getContent().getId();

        reviewRepository.delete(review);

        publishContentSearchStatisticsSyncEvent(contentId);

        publishRecommendationPreferenceChangedEvent(userId);
    }

    private User findActiveUser(UUID userId) {
        return userRepository
                .findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new MoplException(
                        UserErrorCode.USER_NOT_FOUND,
                        "존재하지 않는 사용자입니다. userId="
                                + userId));
    }

    private void validateAuthor(Review review, UUID userId) {
        if (!review.isWrittenBy(userId)) {
            throw new ReviewAccessDeniedException();
        }
    }

    private void publishReviewCreatedEvent(User user, Content content) {
        List<UUID> followerIds = followRepository.findFollowers(user.getId(), Pageable.unpaged())
                .stream()
                .map(User::getId)
                .toList();

        eventPublisher.publishEvent(new FollowingReviewCreatedEvent(
                followerIds, user.getName(), content.getTitle()
        ));
    }

    private void publishContentSearchStatisticsSyncEvent(UUID contentId) {
        eventPublisher.publishEvent(
                new ContentSearchStatisticsSyncEvent(contentId)
        );
    }

    private void publishRecommendationPreferenceChangedEvent(UUID userId) {
        eventPublisher.publishEvent(
                new RecommendationPreferenceChangedEvent(userId)
        );
    }
}
