package com.mopl.recommendation.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mopl.auth.dto.AuthUser;
import com.mopl.core.common.enums.ContentType;
import com.mopl.recommendation.dto.RecommendationItem;
import com.mopl.recommendation.service.RecommendationService;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

    @Mock
    private RecommendationService recommendationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RecommendationController recommendationController =
                new RecommendationController(
                        recommendationService
                );

        mockMvc =
                MockMvcBuilders
                        .standaloneSetup(
                                recommendationController
                        )
                        .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsRecommendationsForCurrentUser()
            throws Exception {
        UUID userId =
                UUID.randomUUID();

        UUID contentId =
                UUID.randomUUID();

        authenticate(userId);

        RecommendationItem recommendation =
                new RecommendationItem(
                        contentId,
                        "Interstellar",
                        ContentType.MOVIE,
                        List.of(),
                        0.95,
                        8.7,
                        120.0,
                        5000L,
                        "SF 콘텐츠를 선호하는 취향과 잘 맞습니다."
                );

        when(recommendationService.getRecommendations(userId))
                .thenReturn(
                        List.of(recommendation)
                );

        mockMvc.perform(
                        get("/api/recommendations")
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$", hasSize(1))
                )
                .andExpect(
                        jsonPath("$[0].contentId")
                                .value(
                                        contentId.toString()
                                )
                )
                .andExpect(
                        jsonPath("$[0].title")
                                .value("Interstellar")
                )
                .andExpect(
                        jsonPath("$[0].type")
                                .value("MOVIE")
                )
                .andExpect(
                        jsonPath("$[0].reason")
                                .value(
                                        "SF 콘텐츠를 선호하는 취향과 잘 맞습니다."
                                )
                );

        verify(recommendationService)
                .getRecommendations(userId);
    }

    @Test
    void returnsEmptyArrayWhenThereAreNoRecommendations()
            throws Exception {
        UUID userId =
                UUID.randomUUID();

        authenticate(userId);

        when(recommendationService.getRecommendations(userId))
                .thenReturn(
                        List.of()
                );

        mockMvc.perform(
                        get("/api/recommendations")
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$", hasSize(0))
                );

        verify(recommendationService)
                .getRecommendations(userId);
    }

    private void authenticate(UUID userId) {
        AuthUser authUser =
                new AuthUser(
                        userId,
                        "recommendation-test@mopl.com",
                        null
                );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        authUser,
                        null,
                        List.of()
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(authentication);
    }
}