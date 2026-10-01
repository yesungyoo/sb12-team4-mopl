package com.mopl.recommendation.dto;

import com.mopl.core.common.enums.ContentType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record RecommendationPreference(
        boolean coldStart,
        String preferenceText,
        Set<UUID> interactedContentIds,
        Map<ContentType, List<RecommendationPreferredTag>> preferredTagsByType
) {

    // 기존 RecommendationCandidateServiceTest 등 기존 호출부와의 호환을 유지한다.
    public static RecommendationPreference personalized(
            String preferenceText,
            Set<UUID> interactedContentIds
    ) {
        return personalized(
                preferenceText,
                interactedContentIds,
                Map.of()
        );
    }

    public static RecommendationPreference forColdStart() {
        return new RecommendationPreference(
                true,
                "",
                Set.of(),
                Map.of()
        );
    }

    public static RecommendationPreference personalized(
            String preferenceText,
            Set<UUID> interactedContentIds,
            Map<ContentType, List<RecommendationPreferredTag>> preferredTagsByType
    ) {
        return new RecommendationPreference(
                false,
                preferenceText,
                Set.copyOf(interactedContentIds),
                copyPreferredTagsByType(preferredTagsByType)
        );
    }

    // 외부에서 반환된 Map/List를 변경해 RecommemdationPreference 내부 값이 바뀌지 않도록 방어적
    private static Map<ContentType, List<RecommendationPreferredTag>> copyPreferredTagsByType(
            Map<ContentType, List<RecommendationPreferredTag>> preferredTagsByType
    ) {
        if (preferredTagsByType == null || preferredTagsByType.isEmpty()) {
            return Map.of();
        }

        return preferredTagsByType.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())
                ));
    }
}
