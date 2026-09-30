package com.mopl.recommendation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mopl.recommendation.config.RecommendationProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.content.search.dto.ContentTagDto;
import com.mopl.infrastructure.ai.client.LlmClient;
import com.mopl.infrastructure.ai.dto.LlmRequest;
import com.mopl.infrastructure.ai.dto.LlmResponse;
import com.mopl.infrastructure.ai.exception.AiClientException;
import com.mopl.recommendation.dto.RecommendationItem;
import com.mopl.recommendation.dto.RecommendationLlmResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationRerankService {

    private static final String DEFAULT_REASON = "사용자 취향과 유사한 콘텐츠입니다.";
    private static final String SYSTEM_PROMPT = """
            당신은 콘텐츠 개인화 추천 순위 결정 시스템입니다.

            사용자 취향 정보와 서버가 제공한 후보 콘텐츠 목록을 바탕으로
            사용자가 좋아할 가능성이 높은 순서대로 콘텐츠를 선택하세요.

            반드시 다음 JSON 형식만 반환하세요.

            {
              "items": [
                {
                  "contentId": "후보 목록에 존재하는 UUID",
                  "reason": "추천 이유"
                }
              ]
            }

            규칙:
            1. contentId는 반드시 제공된 후보 목록의 contentId만 사용하세요.
            2. 후보 목록에 없는 contentId를 생성하지 마세요.
            3. 동일한 contentId를 중복해서 반환하지 마세요.
            4. 서버가 요청한 최대 추천 개수를 초과하지 마세요.
            5. reason은 한국어로 간결하게 작성하세요.
            6. JSON 외의 설명, Markdown, 코드 블록을 출력하지 마세요.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final RecommendationProperties recommendationProperties;

    public List<RecommendationItem> rerank(
            String preferenceText,
            List<ContentCandidate> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        try {
            LlmRequest request = new LlmRequest(
                    SYSTEM_PROMPT,
                    buildUserPrompt(
                            preferenceText,
                            candidates
                    ),
                    4000,
                    LlmRequest.ReasoningEffort.NONE
            );

            LlmResponse response = llmClient.generate(request);

            List<RecommendationItem> rerankedItems = parseAndValidate(
                    response.content(),
                    candidates
            );

            return fillRemainingCandidates(
                    rerankedItems,
                    candidates
            );
        } catch (AiClientException | JsonProcessingException exception) {
            log.warn("개인화 추천 LLM rerank에 실패하여 semantic 순서로 fallback 합니다.",
                    exception);

            return createSemanticFallback(candidates);
        }
    }

    private String buildUserPrompt(String preferenceText, List<ContentCandidate> candidates) {
        StringBuilder builder = new StringBuilder();

        builder.append("사용자 취향:\n")
                .append(preferenceText)
                .append("\n\n")
                .append("후보 콘텐츠: \n");

        for (ContentCandidate candidate : candidates) {
            builder.append("- contentId: ")
                    .append(candidate.contentId())
                    .append('\n')
                    .append("  title: ")
                    .append(candidate.title())
                    .append('\n')
                    .append("  type: ")
                    .append(candidate.type())
                    .append('\n')
                    .append("  tags: ")
                    .append(formatTags(candidate.tags()))
                    .append('\n')
                    .append("  semanticScore: ")
                    .append(candidate.semanticScore())
                    .append('\n')
                    .append("  externalRating: ")
                    .append(candidate.externalRating())
                    .append('\n')
                    .append("  externalPopularity: ")
                    .append(candidate.externalPopularity())
                    .append('\n')
                    .append("  externalVoteCount: ")
                    .append(candidate.externalVoteCount())
                    .append("\n\n");
        }

            return builder.toString().trim();
    }

    private String formatTags(List<ContentTagDto> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }

        return tags.stream()
                .map(tag -> tag.tag() + ":" + tag.value())
                .collect(Collectors.joining(", "));
    }

    private List<RecommendationItem> parseAndValidate(
            String responseContent,
            List<ContentCandidate> candidates
    ) throws JsonProcessingException {
        String normalizedContent = normalizeJson(responseContent);

        RecommendationLlmResponse response = objectMapper.readValue(
                normalizedContent,
                RecommendationLlmResponse.class
        );

        if (response.items() == null || response.items().isEmpty()) {
            return List.of();
        }

        Map<UUID, ContentCandidate> candidateById = new LinkedHashMap<>();

        for (ContentCandidate candidate : candidates) {
            candidateById.putIfAbsent(
                    candidate.contentId(),
                    candidate
            );
        }

        Set<UUID> selectedIds = new LinkedHashSet<>();

        List<RecommendationItem> results = new ArrayList<>();

        for (RecommendationLlmResponse.Item item : response.items()) {
            if (results.size() >= recommendationProperties.resultSize()) {
                break;
            }

            UUID contentId = parseContentId(item.contentId());

            if (contentId == null) {
                continue;
            }

            ContentCandidate candidate = candidateById.get(contentId);

            if (candidate == null) {
                continue;
            }

            if (!selectedIds.add(contentId)) {
                continue;
            }

            String reason = StringUtils.hasText(item.reason())
                    ? item.reason().trim()
                    : DEFAULT_REASON;

            results.add(RecommendationItem.from(
                    candidate,
                    reason
            ));
        }

        return results;
    }

    private UUID parseContentId(String contentId) {
        if (!StringUtils.hasText(contentId)) {
            return null;
        }

        try {
            return UUID.fromString(contentId.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<RecommendationItem> fillRemainingCandidates(
            List<RecommendationItem> rerankedItems,
            List<ContentCandidate> candidates
    ) {
        if (rerankedItems.size() >= recommendationProperties.resultSize()) {
            return rerankedItems.stream()
                    .limit(recommendationProperties.resultSize())
                    .toList();
        }

        List<RecommendationItem> results = new ArrayList<>(rerankedItems);

        Set<UUID> selectedIds = results.stream()
                .map(RecommendationItem::contentId)
                .collect(Collectors.toSet());

        for (ContentCandidate candidate : candidates) {
            if (results.size() >= recommendationProperties.resultSize()) {
                break;
            }

            if (!selectedIds.add(candidate.contentId())) {
                continue;
            }

            results.add(RecommendationItem.from(
                    candidate,
                    DEFAULT_REASON
            ));
        }

        return List.copyOf(results);
    }

    private List<RecommendationItem> createSemanticFallback(
            List<ContentCandidate> candidates
    ) {
        return candidates.stream()
                .limit(recommendationProperties.resultSize())
                .map(candidate -> RecommendationItem.from(
                                candidate,
                                DEFAULT_REASON
                        )
                )
                .toList();
    }

    private String normalizeJson(String content)
            throws JsonProcessingException {
        if (!StringUtils.hasText(content)) {
            throw new JsonProcessingException(
                    "LLM recommendation response is empty."
            ) {
            };
        }

        String normalized = content.trim();

        // Prompt를 무시하고 ```json 코드 블록을 반환하는 경우 방어
        if (normalized.startsWith("```")) {
            int firstLineBreak = normalized.indexOf('\n');

            if (firstLineBreak >= 0) {
                normalized = normalized.substring(
                                firstLineBreak + 1
                        );
            }

            if (normalized.endsWith("```")) {
                normalized = normalized.substring(
                                0,
                                normalized.length() - 3
                        );
            }

            normalized = normalized.trim();
        }

        // JSON 앞뒤에 불필요한 설명이 붙어도 객체 부분만 추출
        int startIndex = normalized.indexOf('{');

        int endIndex = normalized.lastIndexOf('}');

        if (startIndex < 0 || endIndex < startIndex) {
            throw new JsonProcessingException(
                    "LLM recommendation response does not contain JSON."
            ) {
            };
        }

        return normalized.substring(
                startIndex,
                endIndex + 1
        );
    }
}
