package com.mopl.recommendation.controller;

import java.util.List;
import java.util.UUID;

import com.mopl.recommendation.dto.RecommendationSectionsResponse;
import com.mopl.recommendation.dto.RecommendationTab;
import com.mopl.recommendation.service.RecommendationSectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.recommendation.dto.RecommendationItem;
import com.mopl.recommendation.service.RecommendationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final RecommendationSectionService recommendationSectionService;

    @GetMapping
    public ResponseEntity<List<RecommendationItem>> getRecommendations() {
        // 현재 로그인한 사용자를 기준으로 개인화 추천 조회
        UUID userId = SecurityUtil.getCurrentUserId();

        List<RecommendationItem> recommendations =
                recommendationService.getRecommendations(userId);

        return ResponseEntity.ok(recommendations);
    }

    @GetMapping("/sections")
    public ResponseEntity<RecommendationSectionsResponse> getRecommendationSections(
            @RequestParam RecommendationTab tab
    ) {
        UUID userId = SecurityUtil.getCurrentUserId();

        RecommendationSectionsResponse response =
                recommendationSectionService.getSections(
                        userId,
                        tab
                );

        return ResponseEntity.ok(response);
    }
}
