package com.mopl.recommendation.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.mopl.recommendation.dto.RecommendationSection;
import com.mopl.recommendation.dto.RecommendationSectionsResponse;
import com.mopl.recommendation.dto.RecommendationTab;
import com.mopl.recommendation.service.RecommendationSectionService;
import com.mopl.recommendation.service.RecommendationService;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private RecommendationSectionService recommendationSectionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RecommendationController recommendationController =
                new RecommendationController(
                        recommendationService,
                        recommendationSectionService
                );

        mockMvc = MockMvcBuilders
                .standaloneSetup(recommendationController)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getRecommendationsReturnsRecommendations() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        authenticate(userId);

        RecommendationItem recommendation =
                new RecommendationItem(
                        contentId,
                        "Interstellar",
                        "https://example.com/interstellar.jpg",
                        ContentType.MOVIE,
                        List.of(),
                        0.95,
                        8.7,
                        120.0,
                        5000L,
                        "추천 이유"
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
                        jsonPath("$[0].contentId")
                                .value(contentId.toString())
                )
                .andExpect(
                        jsonPath("$[0].title")
                                .value("Interstellar")
                )
                .andExpect(
                        jsonPath("$[0].thumbnailUrl")
                                .value(
                                        "https://example.com/interstellar.jpg"
                                )
                );

        verify(recommendationService)
                .getRecommendations(userId);
    }

    @Test
    void getRecommendationSectionsBindsHomeTab() throws Exception {
        UUID userId = UUID.randomUUID();

        authenticate(userId);

        RecommendationSection section =
                new RecommendationSection(
                        "AI_PERSONALIZED",
                        "소현님을 위한 AI 추천",
                        "취향과 이용 기록을 바탕으로 추천한 콘텐츠예요.",
                        null,
                        null,
                        List.of()
                );

        RecommendationSectionsResponse response =
                RecommendationSectionsResponse.of(
                        List.of(section)
                );

        when(recommendationSectionService.getSections(
                userId,
                RecommendationTab.HOME
        )).thenReturn(response);

        mockMvc.perform(
                        get("/api/recommendations/sections")
                                .param(
                                        "tab",
                                        "HOME"
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.sections[0].key")
                                .value("AI_PERSONALIZED")
                )
                .andExpect(
                        jsonPath("$.sections[0].title")
                                .value(
                                        "소현님을 위한 AI 추천"
                                )
                );

        verify(recommendationSectionService)
                .getSections(
                        userId,
                        RecommendationTab.HOME
                );
    }

    @Test
    void getRecommendationSectionsReturnsBadRequestForInvalidTab()
            throws Exception {
        mockMvc.perform(
                        get("/api/recommendations/sections")
                                .param(
                                        "tab",
                                        "INVALID"
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );

        verifyNoInteractions(
                recommendationSectionService
        );
    }

    @Test
    void getRecommendationSectionsReturnsBadRequestWhenTabIsMissing()
            throws Exception {
        mockMvc.perform(
                        get("/api/recommendations/sections")
                )
                .andExpect(
                        status().isBadRequest()
                );

        verifyNoInteractions(
                recommendationSectionService
        );
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