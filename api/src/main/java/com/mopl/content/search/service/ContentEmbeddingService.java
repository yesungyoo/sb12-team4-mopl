package com.mopl.content.search.service;

import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContentEmbeddingService {

    private final EmbeddingClient embeddingClient;

    public List<Double> embedContent(Content content, List<ContentTag> contentTags) {
        String input = buildEmbeddingInput(content, contentTags);

        return embeddingClient.embed(
                new EmbeddingRequest(input)
        ).embedding();

    }

    private String buildEmbeddingInput(Content content, List<ContentTag> contentTags) {
        StringBuilder builder = new StringBuilder()
                .append("유형: ")
                .append(content.getType().name())
                .append('\n')
                .append("제목: ")
                .append(content.getTitle());

        if (StringUtils.hasText(content.getDescription())) {
            builder.append('\n')
                    .append("설명: ")
                    .append(content.getDescription());
        }

        if (!contentTags.isEmpty()) {
            List<String> tags = contentTags.stream()
                    .sorted(Comparator.comparing(ContentTag::getTag)
                            .thenComparing(ContentTag::getValue))
                    .map(contentTag -> contentTag.getTag() + "=" + contentTag.getValue())
                    .toList();

            builder.append('\n')
                    .append("태그: ")
                    .append(String.join(", ", tags));
        }

        return builder.toString();
    }
}
