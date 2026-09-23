package com.mopl.content.search.dto;

import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.Content;

import java.util.List;
import java.util.UUID;

public record ContentCandidate(
        UUID contentId,
        String title,
        ContentType type,
        List<ContentTagDto> tags,
        double semanticScore,
        Double externalRating,
        Double externalPopularity,
        Long externalVoteCount
) {

    public static ContentCandidate from(
            Content content,
            ContentSearchDocument searchDocument,
            double semanticScore
    ) {
        List<ContentTagDto> tags = searchDocument.getTags() == null
                ? List.of()
                : searchDocument.getTags().stream()
                        .map(ContentTagDto::from)
                        .toList();

        return new ContentCandidate(
                content.getId(),
                content.getTitle(),
                content.getType(),
                tags,
                semanticScore,
                content.getExternalRating() == null ? null : content.getExternalRating().doubleValue(),
                content.getExternalPopularity() == null ? null : content.getExternalPopularity().doubleValue(),
                content.getExternalVoteCount()
        );
    }
}
