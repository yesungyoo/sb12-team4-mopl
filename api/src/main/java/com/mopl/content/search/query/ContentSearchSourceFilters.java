package com.mopl.content.search.query;

import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.SourceFilter;

public final class ContentSearchSourceFilters {

    private static final String EMBEDDING_FIELD = "embedding";

    private ContentSearchSourceFilters() {
    }

    public static SourceFilter withoutEmbedding() {
        return FetchSourceFilter.of(
                builder -> builder.withExcludes(EMBEDDING_FIELD)
        );
    }
}
