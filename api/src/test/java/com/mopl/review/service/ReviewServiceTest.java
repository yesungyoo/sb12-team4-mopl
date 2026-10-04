package com.mopl.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.review.ReviewAccessDeniedException;
import com.mopl.common.exception.review.ReviewAlreadyExistsException;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.event.ContentSearchStatisticsSyncEvent;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.event.FollowingReviewCreatedEvent;
import com.mopl.core.common.event.RecommendationPreferenceChangedEvent;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.review.entity.Review;
import com.mopl.core.domain.user.entity.User;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewResponse;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.repository.ReviewRepository;
import com.mopl.user.repository.FollowRepository;
import com.mopl.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ReviewService reviewService;

    @Test
    @DisplayName("콘텐츠별 리뷰 목록을 커서 방식으로 조회한다")
    void getReviewsSuccess() {
        UUID contentId = UUID.randomUUID();

        Review firstReview = createReview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                contentId,
                "첫 번째 리뷰"
        );

        Review secondReview = createReview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                contentId,
                "두 번째 리뷰"
        );

        when(contentRepository.existsByIdAndDeletedAtIsNull(contentId))
                .thenReturn(true);

        when(reviewRepository.count(anySpecification()))
                .thenReturn(2L);

        when(reviewRepository.findAll(
                anySpecification(),
                any(Pageable.class)
        )).thenReturn(
                new PageImpl<>(List.of(firstReview, secondReview))
        );

        CursorResponse<ReviewResponse> response = reviewService.getReviews(
                contentId,
                null,
                null,
                20,
                "createdAt",
                "DESCENDING"
        );

        assertThat(response.data()).hasSize(2);
        assertThat(response.totalCount()).isEqualTo(2L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.nextIdAfter()).isNull();
        assertThat(response.sortBy()).isEqualTo("createdAt");
        assertThat(response.sortDirection()).isEqualTo("DESCENDING");
    }

    @Test
    @DisplayName("다음 리뷰가 있으면 마지막 리뷰 기준 커서를 반환한다")
    void getReviewsReturnsNextCursor() {
        UUID firstReviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        Review firstReview = createReview(
                firstReviewId,
                UUID.randomUUID(),
                contentId,
                "첫 번째 리뷰"
        );

        when(firstReview.getCreatedAt())
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                9,
                                9,
                                12,
                                0
                        )
                );

        Review secondReview = mock(Review.class);

        when(reviewRepository.count(anySpecification()))
                .thenReturn(2L);

        when(reviewRepository.findAll(
                anySpecification(),
                any(Pageable.class)
        )).thenReturn(
                new PageImpl<>(List.of(firstReview, secondReview))
        );

        CursorResponse<ReviewResponse> response = reviewService.getReviews(
                null,
                null,
                null,
                1,
                "createdAt",
                "DESCENDING"
        );

        assertThat(response.data()).hasSize(1);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor())
                .isEqualTo("2026-09-09T12:00");
        assertThat(response.nextIdAfter())
                .isEqualTo(firstReviewId.toString());
    }

    @Test
    @DisplayName("평점 정렬에서는 평점 값을 다음 커서로 반환한다")
    void getReviewsReturnsRatingCursor() {
        UUID firstReviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        Review firstReview = createReview(
                firstReviewId,
                UUID.randomUUID(),
                contentId,
                "첫 번째 리뷰"
        );

        Review secondReview = mock(Review.class);

        when(reviewRepository.count(anySpecification()))
                .thenReturn(2L);

        when(reviewRepository.findAll(
                anySpecification(),
                any(Pageable.class)
        )).thenReturn(
                new PageImpl<>(List.of(firstReview, secondReview))
        );

        CursorResponse<ReviewResponse> response = reviewService.getReviews(
                null,
                null,
                null,
                1,
                "rating",
                "DESCENDING"
        );

        assertThat(response.data()).hasSize(1);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo("4.5");
        assertThat(response.nextIdAfter())
                .isEqualTo(firstReviewId.toString());
    }

    @Test
    @DisplayName("존재하지 않거나 삭제된 콘텐츠의 리뷰 목록은 조회할 수 없다")
    void getReviewsContentNotFound() {
        UUID contentId = UUID.randomUUID();

        when(contentRepository.existsByIdAndDeletedAtIsNull(contentId))
                .thenReturn(false);

        assertThatThrownBy(() -> reviewService.getReviews(
                contentId,
                null,
                null,
                20,
                "createdAt",
                "DESCENDING"
        )).isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    @DisplayName("cursor와 idAfter 중 하나만 전달하면 조회할 수 없다")
    void getReviewsInvalidCursorPair() {
        assertThatThrownBy(() -> reviewService.getReviews(
                null,
                "2026-09-09T12:00",
                null,
                20,
                "createdAt",
                "DESCENDING"
        )).isInstanceOf(MoplException.class);
    }

    @Test
    @DisplayName("리뷰 생성에 성공한다")
    void createReviewSuccess() {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID followerId = UUID.randomUUID();

        User user = mock(User.class);
        User follower = mock(User.class);
        Content content = mock(Content.class);

        ReviewCreateRequest request = new ReviewCreateRequest(
                contentId,
                "좋은 콘텐츠입니다.",
                new BigDecimal("4.5")
        );

        Review savedReview = createReview(
                reviewId,
                userId,
                contentId,
                request.text()
        );

        when(user.getId()).thenReturn(userId);
        when(user.getName()).thenReturn("리뷰작성자");

        when(follower.getId()).thenReturn(followerId);

        when(content.getTitle()).thenReturn("테스트 콘텐츠");

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.of(user));

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.of(content));

        when(reviewRepository.existsByUserIdAndContentId(
                userId,
                contentId
        )).thenReturn(false);

        when(reviewRepository.save(any(Review.class)))
                .thenReturn(savedReview);

        when(followRepository.findFollowers(
                userId,
                Pageable.unpaged()
        )).thenReturn(
                new PageImpl<>(List.of(follower))
        );

        ReviewResponse response = reviewService.createReview(
                userId,
                request
        );

        assertThat(response.id()).isEqualTo(reviewId);
        assertThat(response.contentId()).isEqualTo(contentId);

        verify(reviewRepository).save(any(Review.class));

        verify(eventPublisher).publishEvent(
                org.mockito.ArgumentMatchers.<Object>argThat(event ->
                        event instanceof FollowingReviewCreatedEvent followingEvent
                                && followingEvent.followerIds().equals(List.of(followerId))
                                && followingEvent.reviewerName().equals("리뷰작성자")
                                && followingEvent.contentTitle().equals("테스트 콘텐츠")
                )
        );

        verify(eventPublisher).publishEvent(
                new ContentSearchStatisticsSyncEvent(contentId)
        );

        verify(eventPublisher).publishEvent(
                new RecommendationPreferenceChangedEvent(userId)
        );
    }

    @Test
    @DisplayName("동일한 콘텐츠에 이미 리뷰가 있으면 생성할 수 없다")
    void createReviewAlreadyExists() {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        User user = mock(User.class);
        Content content = mock(Content.class);

        ReviewCreateRequest request = new ReviewCreateRequest(
                contentId,
                "중복 리뷰",
                new BigDecimal("4.0")
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.of(user));

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.of(content));

        when(reviewRepository.existsByUserIdAndContentId(
                userId,
                contentId
        )).thenReturn(true);

        assertThatThrownBy(
                () -> reviewService.createReview(
                        userId,
                        request
                )
        ).isInstanceOf(ReviewAlreadyExistsException.class);

        verify(reviewRepository, never())
                .save(any(Review.class));

        verify(eventPublisher, never())
                .publishEvent(any());
    }

    @Test
    @DisplayName("존재하지 않는 사용자로 리뷰를 생성하면 예외가 발생한다")
    void createReviewUserNotFound() {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        ReviewCreateRequest request = new ReviewCreateRequest(
                contentId,
                "리뷰",
                new BigDecimal("4.0")
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> reviewService.createReview(
                        userId,
                        request
                )
        ).isInstanceOf(MoplException.class);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠에 리뷰를 생성하면 예외가 발생한다")
    void createReviewContentNotFound() {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        User user = mock(User.class);

        ReviewCreateRequest request = new ReviewCreateRequest(
                contentId,
                "리뷰",
                new BigDecimal("4.0")
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.of(user));

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> reviewService.createReview(
                        userId,
                        request
                )
        ).isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    @DisplayName("작성자는 자신의 리뷰를 수정할 수 있다")
    void updateReviewSuccess() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        Review review = createReview(
                reviewId,
                userId,
                contentId,
                "수정 전 리뷰"
        );

        ReviewUpdateRequest request = new ReviewUpdateRequest(
                new BigDecimal("5.0"),
                "수정 후 리뷰"
        );

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(review));

        when(review.isWrittenBy(userId))
                .thenReturn(true);

        ReviewResponse response = reviewService.updateReview(
                userId,
                reviewId,
                request
        );

        verify(review).update(
                request.rating(),
                request.text()
        );

        assertThat(response.id()).isEqualTo(reviewId);

        verify(eventPublisher).publishEvent(
                new ContentSearchStatisticsSyncEvent(contentId)
        );

        verify(eventPublisher).publishEvent(
                new RecommendationPreferenceChangedEvent(userId)
        );
    }

    @Test
    @DisplayName("다른 사용자의 리뷰는 수정할 수 없다")
    void updateReviewAccessDenied() {
        UUID currentUserId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();

        Review review = mock(Review.class);

        ReviewUpdateRequest request = new ReviewUpdateRequest(
                new BigDecimal("5.0"),
                "수정 시도"
        );

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(review));

        when(review.isWrittenBy(currentUserId))
                .thenReturn(false);

        assertThatThrownBy(
                () -> reviewService.updateReview(
                        currentUserId,
                        reviewId,
                        request
                )
        ).isInstanceOf(ReviewAccessDeniedException.class);

        verify(review, never())
                .update(any(), any());
    }

    @Test
    @DisplayName("작성자는 자신의 리뷰를 삭제할 수 있다")
    void deleteReviewSuccess() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        Review review = mock(Review.class);
        Content content = mock(Content.class);

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(review));

        when(review.isWrittenBy(userId))
                .thenReturn(true);

        when(review.getContent())
                .thenReturn(content);

        when(content.getId())
                .thenReturn(contentId);

        reviewService.deleteReview(
                userId,
                reviewId
        );

        verify(reviewRepository).delete(review);

        verify(eventPublisher).publishEvent(
                new ContentSearchStatisticsSyncEvent(contentId)
        );

        verify(eventPublisher).publishEvent(
                new RecommendationPreferenceChangedEvent(userId)
        );
    }

    @Test
    @DisplayName("다른 사용자의 리뷰는 삭제할 수 없다")
    void deleteReviewAccessDenied() {
        UUID currentUserId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();

        Review review = mock(Review.class);

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(review));

        when(review.isWrittenBy(currentUserId))
                .thenReturn(false);

        assertThatThrownBy(
                () -> reviewService.deleteReview(
                        currentUserId,
                        reviewId
                )
        ).isInstanceOf(ReviewAccessDeniedException.class);

        verify(reviewRepository, never())
                .delete(review);
    }

    @SuppressWarnings("unchecked")
    private Specification<Review> anySpecification() {
        return any(Specification.class);
    }

    private Review createReview(
            UUID reviewId,
            UUID userId,
            UUID contentId,
            String text
    ) {
        User user = mock(User.class);
        Content content = mock(Content.class);
        Review review = mock(Review.class);

        when(user.getId()).thenReturn(userId);
        when(user.getDeletedAt()).thenReturn(null);
        when(user.getName()).thenReturn("리뷰작성자");
        when(user.getProfileImageUrl()).thenReturn(null);

        when(content.getId()).thenReturn(contentId);

        when(review.getId()).thenReturn(reviewId);
        when(review.getUser()).thenReturn(user);
        when(review.getContent()).thenReturn(content);
        when(review.getRating()).thenReturn(new BigDecimal("4.5"));
        when(review.getText()).thenReturn(text);

        return review;
    }
}
