package com.mopl.recommendation.dto;

import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.content.search.dto.ContentTagDto;
import com.mopl.core.common.enums.ContentType;

import java.util.List;
import java.util.UUID;

public record RecommendationItem(
        UUID contentId,
        String title,
        String thumbnailUrl,
        ContentType type,
        List<ContentTagDto> tags,
        double semanticScore,
        Double externalRating,
        Double externalPopularity,
        Long externalVoteCount,
        String reason
) {

    public RecommendationItem(
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
        this(
                contentId,
                title,
                null,
                type,
                tags,
                semanticScore,
                externalRating,
                externalPopularity,
                externalVoteCount,
                reason
        );
    }

    public static RecommendationItem from(
            ContentCandidate candidate,
            String reason
    ) {
        return new RecommendationItem(
                candidate.contentId(),
                candidate.title(),
                candidate.thumbnailUrl(),
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
