package com.mopl.content.search.dto;

import com.mopl.content.search.document.ContentTagSearchDocument;

public record ContentTagDto(
        String tag,
        String value
) {

    public static ContentTagDto from(ContentTagSearchDocument contentTag) {
        return new ContentTagDto(
                contentTag.getTag(),
                contentTag.getValue()
        );
    }
}
