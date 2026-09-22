package com.mopl.review.controller;

import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.review.ReviewNotFoundException;
import com.mopl.review.dto.ReviewAuthorResponse;
import com.mopl.review.dto.ReviewListResponse;
import com.mopl.review.dto.ReviewResponse;
import com.mopl.review.service.ReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.auth.dto.AuthUser;
import com.mopl.common.exception.review.ReviewAccessDeniedException;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewUpdateRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@WebMvcTest(ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID currentUserId;

    @MockitoBean
    private ReviewService reviewService;

    @BeforeEach
    void setUpAuthentication() {
        currentUserId = UUID.randomUUID();

        AuthUser authUser = org.mockito.Mockito.mock(AuthUser.class);

        when(authUser.userId())
                .thenReturn(currentUserId);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        authUser,
                        null,
                        List.of()
                );

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("리뷰 단건 조회에 성공하면 200을 반환한다")
    void getReviewSuccess() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        when(reviewService.getReview(reviewId))
                .thenReturn(createResponse(reviewId, contentId));

        mockMvc.perform(get("/reviews/{reviewId}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reviewId.toString()))
                .andExpect(jsonPath("$.contentId").value(contentId.toString()))
                .andExpect(jsonPath("$.rating").value(4.5))
                .andExpect(jsonPath("$.text").value("테스트 리뷰"));
    }

    @Test
    @DisplayName("존재하지 않는 리뷰 조회 시 404를 반환한다")
    void getReviewNotFound() throws Exception {
        UUID reviewId = UUID.randomUUID();

        when(reviewService.getReview(reviewId))
                .thenThrow(new ReviewNotFoundException());

        mockMvc.perform(get("/reviews/{reviewId}", reviewId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_001"));
    }

    @Test
    @DisplayName("콘텐츠별 리뷰 목록 조회에 성공하면 200을 반환한다")
    void getReviewsSuccess() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        ReviewListResponse response = new ReviewListResponse(
                List.of(createResponse(reviewId, contentId)),
                0, 20, 1, 1);

        when(reviewService.getReviews(eq(contentId), any())).thenReturn(response);

        mockMvc.perform(get("/contents/{contentId}/reviews", contentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠의 리뷰 목록 조회 시 404를 반환한다")
    void getReviewsContentNotFound() throws Exception {
        UUID contentId = UUID.randomUUID();

        when(reviewService.getReviews(eq(contentId), any()))
                .thenThrow(new ContentNotFoundException());

        mockMvc.perform(get("/contents/{contentId}/reviews", contentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_001"));
    }

    private ReviewResponse createResponse(UUID reviewId, UUID contentId) {
        return new ReviewResponse(
                reviewId,
                contentId,
                new ReviewAuthorResponse(
                        UUID.randomUUID(),
                        "리뷰 작성자",
                        null
                ),
                new BigDecimal("4.5"),
                "테스트 리뷰",
                LocalDateTime.of(2026,9,9,12, 0),
                LocalDateTime.of(2026, 9, 9, 12, 0)
        );
    }

    @Test
    @DisplayName("리뷰 생성에 성공하면 201을 반환한다")
    void createReviewSuccess() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        ReviewCreateRequest request = new ReviewCreateRequest(
                new BigDecimal("4.5"),
                "테스트 리뷰"
        );

        when(reviewService.createReview(
                eq(currentUserId),
                eq(contentId),
                any(ReviewCreateRequest.class)
        )).thenReturn(
                createResponse(reviewId, contentId)
        );

        mockMvc.perform(
                        post(
                                "/contents/{contentId}/reviews",
                                contentId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                request
                                        )
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(reviewId.toString())
                )
                .andExpect(
                        jsonPath("$.contentId")
                                .value(contentId.toString())
                )
                .andExpect(
                        jsonPath("$.rating")
                                .value(4.5)
                )
                .andExpect(
                        jsonPath("$.text")
                                .value("테스트 리뷰")
                );

        verify(reviewService).createReview(
                eq(currentUserId),
                eq(contentId),
                any(ReviewCreateRequest.class)
        );
    }

    @Test
    @DisplayName("리뷰 수정에 성공하면 200을 반환한다")
    void updateReviewSuccess() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        ReviewUpdateRequest request = new ReviewUpdateRequest(
                new BigDecimal("5.0"),
                "수정된 리뷰"
        );

        ReviewResponse response = new ReviewResponse(
                reviewId,
                contentId,
                new ReviewAuthorResponse(
                        currentUserId,
                        "리뷰 작성자",
                        null
                ),
                new BigDecimal("5.0"),
                "수정된 리뷰",
                LocalDateTime.of(
                        2026,
                        9,
                        9,
                        12,
                        0
                ),
                LocalDateTime.of(
                        2026,
                        9,
                        9,
                        13,
                        0
                )
        );

        when(reviewService.updateReview(
                eq(currentUserId),
                eq(reviewId),
                any(ReviewUpdateRequest.class)
        )).thenReturn(response);

        mockMvc.perform(
                        patch(
                                "/reviews/{reviewId}",
                                reviewId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                request
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.id")
                                .value(reviewId.toString())
                )
                .andExpect(
                        jsonPath("$.rating")
                                .value(5.0)
                )
                .andExpect(
                        jsonPath("$.text")
                                .value("수정된 리뷰")
                );
    }

    @Test
    @DisplayName("다른 사용자의 리뷰를 수정하면 403을 반환한다")
    void updateReviewAccessDenied() throws Exception {
        UUID reviewId = UUID.randomUUID();

        ReviewUpdateRequest request = new ReviewUpdateRequest(
                new BigDecimal("5.0"),
                "수정 시도"
        );

        when(reviewService.updateReview(
                eq(currentUserId),
                eq(reviewId),
                any(ReviewUpdateRequest.class)
        )).thenThrow(
                new ReviewAccessDeniedException()
        );

        mockMvc.perform(
                        patch(
                                "/reviews/{reviewId}",
                                reviewId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                request
                                        )
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value("REVIEW_003")
                );
    }

    @Test
    @DisplayName("리뷰 삭제에 성공하면 204를 반환한다")
    void deleteReviewSuccess() throws Exception {
        UUID reviewId = UUID.randomUUID();

        mockMvc.perform(
                        delete(
                                "/reviews/{reviewId}",
                                reviewId
                        )
                )
                .andExpect(status().isNoContent());

        verify(reviewService).deleteReview(
                currentUserId,
                reviewId
        );
    }

    @Test
    @DisplayName("다른 사용자의 리뷰를 삭제하면 403을 반환한다")
    void deleteReviewAccessDenied() throws Exception {
        UUID reviewId = UUID.randomUUID();

        doThrow(new ReviewAccessDeniedException())
                .when(reviewService)
                .deleteReview(
                        currentUserId,
                        reviewId
                );

        mockMvc.perform(
                        delete(
                                "/reviews/{reviewId}",
                                reviewId
                        )
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value("REVIEW_003")
                );
    }
}
