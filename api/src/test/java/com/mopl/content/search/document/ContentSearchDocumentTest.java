package com.mopl.content.search.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

class ContentSearchDocumentTest {

    @Test
    void embeddingMetadataFieldsUseKeywordMapping() throws Exception {
        assertKeywordField("contentInputHash");
        assertKeywordField("embeddingInputHash");
        assertKeywordField("embeddingModel");
    }

    @Test
    void missingMetadataFieldsRemainNullable() {
        ContentSearchDocument document =
                new ContentSearchDocument();

        assertThat(document.getContentInputHash()).isNull();
        assertThat(document.getEmbeddingInputHash()).isNull();
        assertThat(document.getEmbeddingModel()).isNull();
    }

    @Test
    void existingEmbeddingFactoryRemainsCompatible() {
        ContentSearchDocument document = ContentSearchDocument.from(
                createContent(),
                List.of(),
                List.of(0.1D, 0.2D)
        );

        assertThat(document.getEmbedding())
                .containsExactly(0.1F, 0.2F);
        assertThat(document.getContentInputHash()).isNull();
        assertThat(document.getEmbeddingInputHash()).isNull();
        assertThat(document.getEmbeddingModel()).isNull();
    }

    @Test
    void existingSearchFactoryRemainsCompatible() {
        ContentSearchDocument document = ContentSearchDocument.from(
                createContent(),
                List.of(),
                4.5D,
                3L,
                7L
        );

        assertThat(document.getAverageRating()).isEqualTo(4.5D);
        assertThat(document.getReviewCount()).isEqualTo(3L);
        assertThat(document.getWatcherCount()).isEqualTo(7L);
        assertThat(document.getContentInputHash()).isNull();
    }

    @Test
    void searchFactoryAcceptsContentInputHash() {
        String contentInputHash = "a".repeat(64);

        ContentSearchDocument document = ContentSearchDocument.from(
                createContent(),
                List.of(),
                4.5D,
                3L,
                7L,
                contentInputHash
        );

        assertThat(document.getContentInputHash())
                .isEqualTo(contentInputHash);
        assertThat(document.getEmbedding()).isNull();
        assertThat(document.getEmbeddingInputHash()).isNull();
        assertThat(document.getEmbeddingModel()).isNull();
    }

    private void assertKeywordField(String fieldName) throws Exception {
        Field field = ContentSearchDocument.class
                .getDeclaredField(fieldName)
                .getAnnotation(Field.class);

        assertThat(field).isNotNull();
        assertThat(field.type()).isEqualTo(FieldType.Keyword);
    }

    private Content createContent() {
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(UUID.randomUUID());
        when(content.getType()).thenReturn(ContentType.MOVIE);
        when(content.getTitle()).thenReturn("제목");
        when(content.getExternalSource()).thenReturn(ExternalSource.TMDB);
        return content;
    }
}
