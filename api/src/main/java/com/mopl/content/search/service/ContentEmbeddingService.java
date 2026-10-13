package com.mopl.content.search.service;

import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.config.AiProperties;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContentEmbeddingService {

    private final EmbeddingClient embeddingClient;
    private final AiProperties aiProperties;

    public List<Double> embedContent(Content content, List<ContentTag> contentTags) {
        return embedContentWithMetadata(content, contentTags).embedding();
    }

    public EmbeddingGeneration embedContentWithMetadata(
            Content content,
            List<ContentTag> contentTags
    ) {
        String input = buildEmbeddingInput(content, contentTags);
        String inputHash = calculateInputHash(input);
        String model = aiProperties.embeddingModel();
        List<Double> embedding = embeddingClient.embed(
                new EmbeddingRequest(input)
        ).embedding();

        return new EmbeddingGeneration(
                embedding,
                inputHash,
                model
        );
    }

    public String calculateInputHash(
            Content content,
            List<ContentTag> contentTags
    ) {
        return calculateInputHash(
                buildEmbeddingInput(content, contentTags)
        );
    }

    private String calculateInputHash(String input) {
        try {
            MessageDigest messageDigest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    messageDigest.digest(
                            input.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 hash 알고리즘을 사용할 수 없습니다.",
                    exception
            );
        }
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

    public record EmbeddingGeneration(
            List<Double> embedding,
            String inputHash,
            String model
    ) {
    }
}
