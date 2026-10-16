package com.mopl.content.search.query;

import com.mopl.content.search.document.ContentSearchDocument;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.SourceFilter;

public final class ContentSearchSourceFilters {

    private ContentSearchSourceFilters() {
    }

    public static SourceFilter withoutEmbedding() {
        return FetchSourceFilter.of(
                builder -> builder.withExcludes(
                        ContentSearchDocument.EMBEDDING_FIELD
                )
        );
    }

    public static SourceFilter idOnly() {
        return FetchSourceFilter.of(
                builder -> builder.withIncludes("id")
        );
    }

    public static SourceFilter embeddingMetadataOnly() {
        return FetchSourceFilter.of(
                builder -> builder.withIncludes(
                        "id",
                        "contentInputHash",
                        "embeddingInputHash",
                        "embeddingModel"
                )
        );
    }
}
