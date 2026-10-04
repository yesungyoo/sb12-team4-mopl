package com.mopl.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.auth.dto.AuthUser;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.review.ReviewAccessDeniedException;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.review.dto.ReviewAuthorResponse;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewResponse;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.service.ReviewService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReviewService reviewService;

    private UUID currentUserId;

    @BeforeEach
    void setUpAuthentication() {
        currentUserId = UUID.randomUUID();

        AuthUser authUser = org.mockito.Mockito.mock(AuthUser.class);

        when(authUser.userId()).thenReturn(currentUserId);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        authUser,
                        null,
                        List.of()
                );

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("리뷰 목록 조회에 성공하면 커서 응답과 200을 반환한다")
    void getReviewsSuccess() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        CursorResponse<ReviewResponse> response = CursorResponse.of(
                List.of(createResponse(reviewId, contentId)),
                null,
                null,
                false,
                1L,
                "createdAt",
                "DESCENDING"
        );

        when(reviewService.getReviews(
                eq(contentId),
                isNull(),
                isNull(),
                eq(20),
                eq("createdAt"),
                eq("DESCENDING")
        )).thenReturn(response);

        mockMvc.perform(
                        get("/api/reviews")
                                .param("contentId", contentId.toString())
                                .param("limit", "20")
                                .param("sortDirection", "DESCENDING")
                                .param("sortBy", "createdAt")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(reviewId.toString()))
                .andExpect(jsonPath("$.data[0].contentId").value(contentId.toString()))
                .andExpect(jsonPath("$.data[0].text").value("테스트 리뷰"))
                .andExpect(jsonPath("$.data[0].rating").value(4.5))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.sortBy").value("createdAt"))
                .andExpect(jsonPath("$.sortDirection").value("DESCENDING"));
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠의 리뷰 목록 조회 시 404를 반환한다")
    void getReviewsContentNotFound() throws Exception {
        UUID contentId = UUID.randomUUID();

        when(reviewService.getReviews(
                eq(contentId),
                isNull(),
                isNull(),
                eq(20),
                eq("createdAt"),
                eq("DESCENDING")
        )).thenThrow(new ContentNotFoundException());

        mockMvc.perform(
                        get("/api/reviews")
                                .param("contentId", contentId.toString())
                                .param("limit", "20")
                                .param("sortDirection", "DESCENDING")
                                .param("sortBy", "createdAt")
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_001"));
    }

    @Test
    @DisplayName("리뷰 생성에 성공하면 200을 반환한다")
    void createReviewSuccess() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        ReviewCreateRequest request = new ReviewCreateRequest(
                contentId,
                "테스트 리뷰",
                new BigDecimal("4.5")
        );

        when(reviewService.createReview(
                eq(currentUserId),
                any(ReviewCreateRequest.class)
        )).thenReturn(createResponse(reviewId, contentId));

        mockMvc.perform(
                        post("/api/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reviewId.toString()))
                .andExpect(jsonPath("$.contentId").value(contentId.toString()))
                .andExpect(jsonPath("$.text").value("테스트 리뷰"))
                .andExpect(jsonPath("$.rating").value(4.5));

        verify(reviewService).createReview(
                eq(currentUserId),
                any(ReviewCreateRequest.class)
        );
    }

    @Test
    @DisplayName("리뷰 생성 시 콘텐츠 ID가 없으면 400을 반환한다")
    void createReviewMissingContentId() throws Exception {
        String request = """
                {
                    "text": "테스트 리뷰",
                    "rating": 4.5
                }
                """;

        mockMvc.perform(
                        post("/api/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                )
                .andExpect(status().isBadRequest());
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
                "수정된 리뷰",
                new BigDecimal("5.0")
        );

        when(reviewService.updateReview(
                eq(currentUserId),
                eq(reviewId),
                any(ReviewUpdateRequest.class)
        )).thenReturn(response);

        mockMvc.perform(
                        patch("/api/reviews/{reviewId}", reviewId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reviewId.toString()))
                .andExpect(jsonPath("$.text").value("수정된 리뷰"))
                .andExpect(jsonPath("$.rating").value(5.0));
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
        )).thenThrow(new ReviewAccessDeniedException());

        mockMvc.perform(
                        patch("/api/reviews/{reviewId}", reviewId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REVIEW_003"));
    }

    @Test
    @DisplayName("리뷰 삭제에 성공하면 204를 반환한다")
    void deleteReviewSuccess() throws Exception {
        UUID reviewId = UUID.randomUUID();

        mockMvc.perform(delete("/api/reviews/{reviewId}", reviewId))
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

        mockMvc.perform(delete("/api/reviews/{reviewId}", reviewId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REVIEW_003"));
    }

    private ReviewResponse createResponse(
            UUID reviewId,
            UUID contentId
    ) {
        return new ReviewResponse(
                reviewId,
                contentId,
                new ReviewAuthorResponse(
                        UUID.randomUUID(),
                        "리뷰 작성자",
                        null
                ),
                "테스트 리뷰",
                new BigDecimal("4.5")
        );
    }
}
