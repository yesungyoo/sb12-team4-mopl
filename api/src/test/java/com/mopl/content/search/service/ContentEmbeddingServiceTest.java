package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.config.AiProperties;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentEmbeddingServiceTest {

    private static final String EMBEDDING_MODEL =
            "text-embedding-3-small";

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private AiProperties aiProperties;

    private ContentEmbeddingService contentEmbeddingService;

    @BeforeEach
    void setUp() {
        contentEmbeddingService = new ContentEmbeddingService(
                embeddingClient,
                aiProperties
        );
    }

    @Test
    void embedsExactInputAndReturnsItsHashAndConfiguredModel() {
        Content content = createContent("제목", "설명");
        ContentTag drama = createTag("genre", "drama");
        ContentTag action = createTag("genre", "action");
        List<Double> embedding = List.of(0.1D, 0.2D);

        when(aiProperties.embeddingModel())
                .thenReturn(EMBEDDING_MODEL);
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(embedding));

        ContentEmbeddingService.EmbeddingGeneration result =
                contentEmbeddingService.embedContentWithMetadata(
                        content,
                        List.of(drama, action)
                );

        ArgumentCaptor<EmbeddingRequest> requestCaptor =
                ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddingClient).embed(requestCaptor.capture());

        String expectedInput = "유형: MOVIE\n"
                + "제목: 제목\n"
                + "설명: 설명\n"
                + "태그: genre=action, genre=drama";

        assertThat(requestCaptor.getValue().input())
                .isEqualTo(expectedInput);
        assertThat(result.embedding())
                .isEqualTo(embedding);
        assertThat(result.inputHash())
                .isEqualTo(
                        "164c1b97f4f061c4cf516d34d1fc0d8f"
                                + "d447a1fe4a32474a1192fa458ce6174f"
                )
                .isEqualTo(contentEmbeddingService.calculateInputHash(
                        content,
                        List.of(drama, action)
                ));
        assertThat(result.model())
                .isEqualTo(EMBEDDING_MODEL);
        verify(aiProperties).embeddingModel();
        verify(embeddingClient, times(1))
                .embed(any(EmbeddingRequest.class));
    }

    @Test
    void returnsSameHashForSameInputWithoutEmbeddingRequest() {
        Content content = createContent("제목", "설명");
        List<ContentTag> contentTags = List.of(
                createTag("genre", "drama")
        );

        String firstHash = contentEmbeddingService.calculateInputHash(
                content,
                contentTags
        );
        String secondHash = contentEmbeddingService.calculateInputHash(
                content,
                contentTags
        );

        assertThat(firstHash)
                .isEqualTo(secondHash)
                .matches("[0-9a-f]{64}");
        verifyNoInteractions(embeddingClient, aiProperties);
    }

    @Test
    void changesHashWhenInputChanges() {
        Content original = createContent("제목", "설명");
        Content changed = createContent("변경된 제목", "설명");

        assertThat(contentEmbeddingService.calculateInputHash(
                original,
                List.of()
        )).isNotEqualTo(contentEmbeddingService.calculateInputHash(
                changed,
                List.of()
        ));
        verifyNoInteractions(embeddingClient, aiProperties);
    }

    @Test
    void keepsHashWhenOnlyTagOrderChanges() {
        Content content = createContent("제목", "설명");
        ContentTag drama = createTag("genre", "drama");
        ContentTag action = createTag("genre", "action");

        assertThat(contentEmbeddingService.calculateInputHash(
                content,
                List.of(drama, action)
        )).isEqualTo(contentEmbeddingService.calculateInputHash(
                content,
                List.of(action, drama)
        ));
        verifyNoInteractions(embeddingClient, aiProperties);
    }

    @Test
    void legacyEmbedContentReturnsEmbeddingVector() {
        Content content = createContent("제목", null);
        List<Double> embedding = List.of(0.3D, 0.4D);

        when(aiProperties.embeddingModel())
                .thenReturn(EMBEDDING_MODEL);
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(embedding));

        assertThat(contentEmbeddingService.embedContent(
                content,
                List.of()
        )).isEqualTo(embedding);
        verify(embeddingClient, times(1))
                .embed(any(EmbeddingRequest.class));
    }

    private Content createContent(
            String title,
            String description
    ) {
        Content content = mock(Content.class);
        when(content.getType()).thenReturn(ContentType.MOVIE);
        when(content.getTitle()).thenReturn(title);
        when(content.getDescription()).thenReturn(description);
        return content;
    }

    private ContentTag createTag(
            String tag,
            String value
    ) {
        ContentTag contentTag = mock(ContentTag.class);
        when(contentTag.getTag()).thenReturn(tag);
        when(contentTag.getValue()).thenReturn(value);
        return contentTag;
    }
}
