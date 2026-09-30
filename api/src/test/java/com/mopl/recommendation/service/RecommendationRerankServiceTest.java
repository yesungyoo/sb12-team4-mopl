package com.mopl.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.content.search.dto.ContentTagDto;
import com.mopl.core.common.enums.ContentType;
import com.mopl.infrastructure.ai.client.LlmClient;
import com.mopl.infrastructure.ai.dto.LlmRequest;
import com.mopl.infrastructure.ai.dto.LlmResponse;
import com.mopl.recommendation.config.RecommendationProperties;
import com.mopl.recommendation.dto.RecommendationItem;

@ExtendWith(MockitoExtension.class)
class RecommendationRerankServiceTest {

    @Mock
    private LlmClient llmClient;

    private RecommendationProperties recommendationProperties;

    private RecommendationRerankService recommendationRerankService;

    @BeforeEach
    void setUp() {
        // [추가]
        recommendationProperties =
                new RecommendationProperties(
                        30,
                        10,
                        Duration.ofHours(6)
                );

        // [수정]
        recommendationRerankService =
                new RecommendationRerankService(
                        llmClient,
                        new ObjectMapper(),
                        recommendationProperties
                );
    }

    @Test
    void returnsEmptyResultWhenCandidatesAreEmpty() {
        List<RecommendationItem> result =
                recommendationRerankService.rerank(
                        "선호 콘텐츠 유형: MOVIE",
                        List.of()
                );

        assertThat(result)
                .isEmpty();

        verify(llmClient, never())
                .generate(any());
    }

    @Test
    void reranksCandidatesUsingLlmResponse() {
        ContentCandidate firstCandidate =
                createCandidate(
                        UUID.randomUUID(),
                        "Interstellar",
                        0.95
                );

        ContentCandidate secondCandidate =
                createCandidate(
                        UUID.randomUUID(),
                        "Dark",
                        0.90
                );

        ContentCandidate thirdCandidate =
                createCandidate(
                        UUID.randomUUID(),
                        "Dune",
                        0.85
                );

        String responseJson =
                """
                {
                  "items": [
                    {
                      "contentId": "%s",
                      "reason": "SF와 드라마 취향에 잘 맞습니다."
                    },
                    {
                      "contentId": "%s",
                      "reason": "미스터리한 분위기를 선호하는 취향과 유사합니다."
                    }
                  ]
                }
                """.formatted(
                        thirdCandidate.contentId(),
                        secondCandidate.contentId()
                );

        when(llmClient.generate(any(LlmRequest.class)))
                .thenReturn(
                        new LlmResponse(responseJson)
                );

        List<RecommendationItem> result =
                recommendationRerankService.rerank(
                        """
                        선호 콘텐츠 유형: MOVIE
                        선호 태그:
                        - GENRE:SF
                        """,
                        List.of(
                                firstCandidate,
                                secondCandidate,
                                thirdCandidate
                        )
                );

        assertThat(result)
                .extracting(RecommendationItem::contentId)
                .containsExactly(
                        thirdCandidate.contentId(),
                        secondCandidate.contentId(),
                        firstCandidate.contentId()
                );

        assertThat(result.get(0).reason())
                .isEqualTo(
                        "SF와 드라마 취향에 잘 맞습니다."
                );
    }

    @Test
    void ignoresHallucinatedAndDuplicatedContentIds() {
        ContentCandidate firstCandidate =
                createCandidate(
                        UUID.randomUUID(),
                        "Interstellar",
                        0.95
                );

        ContentCandidate secondCandidate =
                createCandidate(
                        UUID.randomUUID(),
                        "Dune",
                        0.90
                );

        UUID hallucinatedContentId =
                UUID.randomUUID();

        String responseJson =
                """
                {
                  "items": [
                    {
                      "contentId": "%s",
                      "reason": "추천합니다."
                    },
                    {
                      "contentId": "%s",
                      "reason": "존재하지 않는 후보입니다."
                    },
                    {
                      "contentId": "%s",
                      "reason": "중복 추천입니다."
                    }
                  ]
                }
                """.formatted(
                        firstCandidate.contentId(),
                        hallucinatedContentId,
                        firstCandidate.contentId()
                );

        when(llmClient.generate(any(LlmRequest.class)))
                .thenReturn(
                        new LlmResponse(responseJson)
                );

        List<RecommendationItem> result =
                recommendationRerankService.rerank(
                        "선호 콘텐츠 유형: MOVIE",
                        List.of(
                                firstCandidate,
                                secondCandidate
                        )
                );

        assertThat(result)
                .extracting(RecommendationItem::contentId)
                .containsExactly(
                        firstCandidate.contentId(),
                        secondCandidate.contentId()
                );

        assertThat(result)
                .extracting(RecommendationItem::contentId)
                .doesNotContain(
                        hallucinatedContentId
                );
    }

    @Test
    void fallsBackToSemanticOrderWhenLlmResponseIsInvalid() {
        ContentCandidate firstCandidate =
                createCandidate(
                        UUID.randomUUID(),
                        "Interstellar",
                        0.95
                );

        ContentCandidate secondCandidate =
                createCandidate(
                        UUID.randomUUID(),
                        "Dune",
                        0.90
                );

        when(llmClient.generate(any(LlmRequest.class)))
                .thenReturn(
                        new LlmResponse(
                                "this is not valid json"
                        )
                );

        List<RecommendationItem> result =
                recommendationRerankService.rerank(
                        "선호 콘텐츠 유형: MOVIE",
                        List.of(
                                firstCandidate,
                                secondCandidate
                        )
                );

        assertThat(result)
                .extracting(RecommendationItem::contentId)
                .containsExactly(
                        firstCandidate.contentId(),
                        secondCandidate.contentId()
                );

        assertThat(result)
                .allSatisfy(item ->
                        assertThat(item.reason())
                                .isEqualTo(
                                        "사용자 취향과 유사한 콘텐츠입니다."
                                )
                );
    }

    @Test
    void acceptsJsonWrappedInMarkdownCodeFence() {
        ContentCandidate candidate =
                createCandidate(
                        UUID.randomUUID(),
                        "Interstellar",
                        0.95
                );

        String response =
                """
                ```json
                {
                  "items": [
                    {
                      "contentId": "%s",
                      "reason": "우주 SF 취향과 잘 맞습니다."
                    }
                  ]
                }
                ```
                """.formatted(
                        candidate.contentId()
                );

        when(llmClient.generate(any(LlmRequest.class)))
                .thenReturn(
                        new LlmResponse(response)
                );

        List<RecommendationItem> result =
                recommendationRerankService.rerank(
                        "선호 태그: GENRE:SF",
                        List.of(candidate)
                );

        assertThat(result)
                .hasSize(1);

        assertThat(result.getFirst().contentId())
                .isEqualTo(candidate.contentId());

        assertThat(result.getFirst().reason())
                .isEqualTo(
                        "우주 SF 취향과 잘 맞습니다."
                );
    }

    private ContentCandidate createCandidate(
            UUID contentId,
            String title,
            double semanticScore
    ) {
        return new ContentCandidate(
                contentId,
                title,
                ContentType.MOVIE,
                List.of(
                        new ContentTagDto(
                                "GENRE",
                                "SF"
                        )
                ),
                semanticScore,
                8.5,
                100.0,
                1000L
        );
    }
}