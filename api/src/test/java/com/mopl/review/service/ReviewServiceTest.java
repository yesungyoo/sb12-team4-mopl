package com.mopl.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.review.ReviewAccessDeniedException;
import com.mopl.common.exception.review.ReviewAlreadyExistsException;
import com.mopl.common.exception.review.ReviewNotFoundException;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.common.event.FollowingReviewCreatedEvent;
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

@ExtendWith(MockitoExtension.class)
public class ReviewServiceTest {

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
    @DisplayName("리뷰 단건 조회에 성공한다.")
    void getReviewSuccess() {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        Review review = createReview(
                reviewId,
                userId,
                contentId,
                "리뷰 내용"
        );

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(review));

        ReviewResponse response = reviewService.getReview(reviewId);

        assertThat(response.id()).isEqualTo(reviewId);
        assertThat(response.contentId()).isEqualTo(contentId);
        assertThat(response.author().id()).isEqualTo(userId);
        assertThat(response.rating()).isEqualByComparingTo("4.5");
        assertThat(response.text()).isEqualTo("리뷰 내용");
    }

    @Test
    @DisplayName("존재하지 않는 리뷰 조회 시 예외가 발생한다")
    void getReviewNotFound() {
        UUID reviewId = UUID.randomUUID();

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.getReview(reviewId))
                .isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    @DisplayName("콘텐츠별 리뷰 목록을 조회한다")
    void getReviewsSuccess() {
        UUID contentId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);

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

        Page<Review> reviewPage = new PageImpl<>(
                List.of(firstReview, secondReview),
                pageable,
                2
        );

        when(contentRepository.existsByIdAndDeletedAtIsNull(contentId))
                .thenReturn(true);

        when(reviewRepository.findAllByContentId(contentId, pageable))
                .thenReturn(reviewPage);

        ReviewListResponse response = reviewService.getReviews(
                contentId,
                pageable
        );

        assertThat(response.reviews()).hasSize(2);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하지 않거나 삭제된 콘텐츠의 리뷰 목록 조회 시 예외가 발생한다")
    void getReviewsContentNotFound() {
        UUID contentId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);

        when(contentRepository.existsByIdAndDeletedAtIsNull(contentId))
                .thenReturn(false);

        assertThatThrownBy(() -> reviewService.getReviews(contentId, pageable))
                .isInstanceOf(ContentNotFoundException.class);
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
                new BigDecimal("4.5"),
                "좋은 콘텐츠입니다."
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
                contentId,
                request
        );

        assertThat(response.id()).isEqualTo(reviewId);
        assertThat(response.contentId()).isEqualTo(contentId);

        verify(reviewRepository).save(any(Review.class));

        ArgumentCaptor<FollowingReviewCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(
                        FollowingReviewCreatedEvent.class
                );

        verify(eventPublisher).publishEvent(
                eventCaptor.capture()
        );

        FollowingReviewCreatedEvent event =
                eventCaptor.getValue();

        assertThat(event.followerIds())
                .containsExactly(followerId);
        assertThat(event.reviewerName())
                .isEqualTo("리뷰작성자");
        assertThat(event.contentTitle())
                .isEqualTo("테스트 콘텐츠");
    }

    @Test
    @DisplayName("동일한 콘텐츠에 이미 리뷰가 있으면 생성할 수 없다")
    void createReviewAlreadyExists() {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        User user = mock(User.class);
        Content content = mock(Content.class);

        ReviewCreateRequest request = new ReviewCreateRequest(
                new BigDecimal("4.0"),
                "중복 리뷰"
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
                        contentId,
                        request
                )
        )
                .isInstanceOf(
                        ReviewAlreadyExistsException.class
                );

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
                new BigDecimal("4.0"),
                "리뷰"
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> reviewService.createReview(
                        userId,
                        contentId,
                        request
                )
        )
                .isInstanceOf(MoplException.class);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠에 리뷰를 생성하면 예외가 발생한다")
    void createReviewContentNotFound() {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        User user = mock(User.class);

        ReviewCreateRequest request = new ReviewCreateRequest(
                new BigDecimal("4.0"),
                "리뷰"
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.of(user));

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> reviewService.createReview(
                        userId,
                        contentId,
                        request
                )
        )
                .isInstanceOf(ContentNotFoundException.class);
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
        )
                .isInstanceOf(
                        ReviewAccessDeniedException.class
                );

        verify(review, never())
                .update(any(), any());
    }

    @Test
    @DisplayName("작성자는 자신의 리뷰를 삭제할 수 있다")
    void deleteReviewSuccess() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();

        Review review = mock(Review.class);

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(review));

        when(review.isWrittenBy(userId))
                .thenReturn(true);

        reviewService.deleteReview(
                userId,
                reviewId
        );

        verify(reviewRepository).delete(review);
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
        )
                .isInstanceOf(
                        ReviewAccessDeniedException.class
                );

        verify(reviewRepository, never())
                .delete(review);
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
        when(review.getRating())
                .thenReturn(new BigDecimal("4.5"));
        when(review.getText()).thenReturn(text);
        when(review.getCreatedAt())
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                9,
                                9,
                                12,
                                0
                        )
                );
        when(review.getUpdatedAt())
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                9,
                                9,
                                12,
                                0
                        )
                );

        return review;
    }
}
