package com.mopl.recommendation.dto;

import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.content.search.dto.ContentTagDto;
import com.mopl.core.common.enums.ContentType;

import java.util.List;
import java.util.UUID;

public record RecommendationItem(
        UUID contentId,
        String title,
        ContentType type,
        List<ContentTagDto> tags,
        double semanticScore,
        Double externalRating,
        Double externalPopularity,
        Long externalVoteCount,
        String reason
) {

    public static RecommendationItem from(
            ContentCandidate candidate,
            String reason
    ) {
        return new RecommendationItem(
                candidate.contentId(),
                candidate.title(),
                candidate.type(),
                candidate.tags(),
                candidate.semanticScore(),
                candidate.externalRating(),
                candidate.externalPopularity(),
                candidate.externalVoteCount(),
                reason
        );
    }
}
