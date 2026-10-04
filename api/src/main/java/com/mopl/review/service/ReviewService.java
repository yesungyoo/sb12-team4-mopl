package com.mopl.review.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.review.ReviewAccessDeniedException;
import com.mopl.common.exception.review.ReviewAlreadyExistsException;
import com.mopl.common.exception.review.ReviewNotFoundException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.event.ContentSearchStatisticsSyncEvent;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.event.FollowingReviewCreatedEvent;
import com.mopl.core.common.event.RecommendationPreferenceChangedEvent;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.review.entity.Review;
import com.mopl.core.domain.user.entity.User;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewListResponse;
import com.mopl.review.dto.ReviewPageItemResponse;
import com.mopl.review.dto.ReviewResponse;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.repository.ReviewRepository;
import com.mopl.user.repository.FollowRepository;
import com.mopl.user.repository.UserRepository;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private static final int MAX_LIMIT = 100;

    private final ReviewRepository reviewRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReviewResponse getReview(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(ReviewNotFoundException::new);

        return ReviewResponse.from(review);
    }

    public CursorResponse<ReviewResponse> getReviews(
            UUID contentId,
            String cursor,
            UUID idAfter,
            int limit,
            String sortByParam,
            String sortDirectionParam
    ) {
        validateRequest(cursor, idAfter, limit);

        if (contentId != null && !contentRepository.existsByIdAndDeletedAtIsNull(contentId)) {
            throw new ContentNotFoundException();
        }

        int safeLimit = Math.min(limit, MAX_LIMIT);
        ReviewSortBy sortBy = ReviewSortBy.from(sortByParam);
        ReviewSortDirection sortDirection = ReviewSortDirection.from(sortDirectionParam);

        Specification<Review> baseSpecification = buildBaseSpecification(contentId);
        long totalCount = reviewRepository.count(baseSpecification);

        Specification<Review> specification = baseSpecification;

        if (cursor != null) {
            specification = specification.and(
                    buildCursorSpecification(
                            cursor,
                            idAfter,
                            sortBy,
                            sortDirection
                    )
            );
        }

        Pageable pageable = PageRequest.of(
                0,
                safeLimit + 1,
                buildSort(sortBy, sortDirection)
        );

        Page<Review> reviewPage = reviewRepository.findAll(
                specification,
                pageable
        );

        List<Review> reviews = reviewPage.getContent();
        boolean hasNext = reviews.size() > safeLimit;

        List<Review> pageReviews = hasNext
                ? reviews.subList(0, safeLimit)
                : reviews;

        List<ReviewResponse> data = pageReviews.stream()
                .map(ReviewResponse::from)
                .toList();

        String nextCursor = null;
        String nextIdAfter = null;

        if (hasNext && !pageReviews.isEmpty()) {
            Review lastReview = pageReviews.get(pageReviews.size() - 1);

            nextCursor = getCursorValue(lastReview, sortBy);
            nextIdAfter = lastReview.getId().toString();
        }

        return CursorResponse.of(
                data,
                nextCursor,
                nextIdAfter,
                hasNext,
                totalCount,
                sortByParam,
                sortDirectionParam
        );
    }

    public ReviewListResponse getReviews(UUID contentId, Pageable pageable) {
        if (!contentRepository.existsByIdAndDeletedAtIsNull(contentId)) {
            throw new ContentNotFoundException();
        }

        Page<ReviewPageItemResponse> reviewPage = reviewRepository
                .findAllByContentId(contentId, pageable)
                .map(ReviewPageItemResponse::from);

        return ReviewListResponse.from(reviewPage);
    }

    @Transactional
    public ReviewResponse createReview(UUID userId, ReviewCreateRequest request) {
        UUID contentId = request.contentId();

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

    private Specification<Review> buildBaseSpecification(UUID contentId) {
        return (root, query, criteriaBuilder) -> {
            Predicate activeContent = criteriaBuilder.isNull(
                    root.get("content").get("deletedAt")
            );

            if (contentId == null) {
                return activeContent;
            }

            return criteriaBuilder.and(
                    activeContent,
                    criteriaBuilder.equal(
                            root.get("content").get("id"),
                            contentId
                    )
            );
        };
    }

    private Specification<Review> buildCursorSpecification(
            String cursor,
            UUID idAfter,
            ReviewSortBy sortBy,
            ReviewSortDirection sortDirection
    ) {
        return switch (sortBy) {
            case CREATED_AT -> buildCreatedAtCursorSpecification(
                    parseCreatedAtCursor(cursor),
                    idAfter,
                    sortDirection
            );
            case RATING -> buildRatingCursorSpecification(
                    parseRatingCursor(cursor),
                    idAfter,
                    sortDirection
            );
        };
    }

    private Specification<Review> buildCreatedAtCursorSpecification(
            LocalDateTime cursor,
            UUID idAfter,
            ReviewSortDirection sortDirection
    ) {
        return (root, query, criteriaBuilder) -> {
            Path<LocalDateTime> createdAt = root.get("createdAt");
            Path<UUID> id = root.get("id");

            Predicate primary = sortDirection == ReviewSortDirection.ASCENDING
                    ? criteriaBuilder.greaterThan(createdAt, cursor)
                    : criteriaBuilder.lessThan(createdAt, cursor);

            Predicate tieBreaker = criteriaBuilder.and(
                    criteriaBuilder.equal(createdAt, cursor),
                    criteriaBuilder.greaterThan(id, idAfter)
            );

            return criteriaBuilder.or(primary, tieBreaker);
        };
    }

    private Specification<Review> buildRatingCursorSpecification(
            BigDecimal cursor,
            UUID idAfter,
            ReviewSortDirection sortDirection
    ) {
        return (root, query, criteriaBuilder) -> {
            Path<BigDecimal> rating = root.get("rating");
            Path<UUID> id = root.get("id");

            Predicate primary = sortDirection == ReviewSortDirection.ASCENDING
                    ? criteriaBuilder.greaterThan(rating, cursor)
                    : criteriaBuilder.lessThan(rating, cursor);

            Predicate tieBreaker = criteriaBuilder.and(
                    criteriaBuilder.equal(rating, cursor),
                    criteriaBuilder.greaterThan(id, idAfter)
            );

            return criteriaBuilder.or(primary, tieBreaker);
        };
    }

    private Sort buildSort(
            ReviewSortBy sortBy,
            ReviewSortDirection sortDirection
    ) {
        Sort.Direction direction = sortDirection == ReviewSortDirection.ASCENDING
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        return Sort.by(
                new Sort.Order(direction, sortBy.field()),
                Sort.Order.asc("id")
        );
    }

    private String getCursorValue(Review review, ReviewSortBy sortBy) {
        return switch (sortBy) {
            case CREATED_AT -> review.getCreatedAt().toString();
            case RATING -> review.getRating().toPlainString();
        };
    }

    private LocalDateTime parseCreatedAtCursor(String cursor) {
        try {
            return LocalDateTime.parse(cursor);
        } catch (DateTimeParseException exception) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private BigDecimal parseRatingCursor(String cursor) {
        try {
            return new BigDecimal(cursor);
        } catch (NumberFormatException exception) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private void validateRequest(
            String cursor,
            UUID idAfter,
            int limit
    ) {
        if (limit <= 0) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }

        if ((cursor == null) != (idAfter == null)) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private User findActiveUser(UUID userId) {
        return userRepository
                .findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new MoplException(
                        UserErrorCode.USER_NOT_FOUND,
                        "존재하지 않는 사용자입니다. userId=" + userId
                ));
    }

    private void validateAuthor(Review review, UUID userId) {
        if (!review.isWrittenBy(userId)) {
            throw new ReviewAccessDeniedException();
        }
    }

    private void publishReviewCreatedEvent(User user, Content content) {
        List<UUID> followerIds = followRepository.findFollowers(
                        user.getId(),
                        Pageable.unpaged()
                )
                .stream()
                .map(User::getId)
                .toList();

        eventPublisher.publishEvent(
                new FollowingReviewCreatedEvent(
                        followerIds,
                        user.getName(),
                        content.getTitle()
                )
        );
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

    private enum ReviewSortBy {
        CREATED_AT("createdAt"),
        RATING("rating");

        private final String field;

        ReviewSortBy(String field) {
            this.field = field;
        }

        private String field() {
            return field;
        }

        private static ReviewSortBy from(String value) {
            if (value == null) {
                throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
            }

            return switch (value) {
                case "createdAt" -> CREATED_AT;
                case "rating" -> RATING;
                default -> throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
            };
        }
    }

    private enum ReviewSortDirection {
        ASCENDING,
        DESCENDING;

        private static ReviewSortDirection from(String value) {
            if (value == null) {
                throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
            }

            try {
                return ReviewSortDirection.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
            }
        }
    }
}
