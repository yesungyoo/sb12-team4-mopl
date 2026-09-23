package com.mopl.content.search.document;

import com.mopl.core.domain.content.entity.ContentTag;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ContentTagSearchDocument {

    @Field(type = FieldType.Keyword)
    private String tag;

    @Field(type = FieldType.Keyword)
    private String value;

    public static ContentTagSearchDocument from(ContentTag contentTag) {
        return new ContentTagSearchDocument(
                contentTag.getTag(),
                contentTag.getValue()
        );
    }
}
