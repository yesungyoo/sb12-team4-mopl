package com.mopl.batch.content.mapper;

public record ContentSearchSyncRow(
        String contentId,
        String externalSource,
        String type,
        String externalId,
        Boolean deleted
) {

    public ContentExternalIdentifier identifier() {
        return new ContentExternalIdentifier(
                externalSource,
                type,
                externalId
        );
    }
}
