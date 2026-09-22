package com.mopl.content.search.service;

import com.mopl.core.domain.content.entity.Content;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ContentEmbeddingService {

    private final EmbeddingClient embeddingClient;

    public List<Double> embedContent(Content content) {
        String input = buildEmbeddingInput(content);

        return embeddingClient.embed(
                new EmbeddingRequest(input)
        ).embedding();

    }

    private String buildEmbeddingInput(Content content) {
        StringBuilder builder = new StringBuilder()
                .append("제목: ")
                .append(content.getTitle());

        if (StringUtils.hasText(content.getDescription())) {
            builder.append("\n")
                    .append("설명: ")
                    .append(content.getDescription());
        }

        return builder.toString();
    }
}
