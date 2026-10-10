package com.mopl.batch.content.mapper;

import com.mopl.batch.external.common.dto.ExternalContentDto;

public record ContentExternalIdentifier(
        String externalSource,
        String type,
        String externalId
) {

    public static ContentExternalIdentifier from(
            ExternalContentDto content
    ) {
        return new ContentExternalIdentifier(
                content.externalSource(),
                content.type(),
                content.externalId()
        );
    }
}
