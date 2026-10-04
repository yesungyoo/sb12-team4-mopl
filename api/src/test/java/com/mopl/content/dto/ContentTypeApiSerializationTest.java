package com.mopl.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.recommendation.dto.RecommendationItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContentTypeApiSerializationTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesGeneralContentTypesUsingFrontendContract() throws Exception {
        Map<ContentType, String> expectedValues = Map.of(
                ContentType.MOVIE,
                "movie",
                ContentType.TV_SERIES,
                "tvSeries",
                ContentType.SPORT,
                "sport"
        );

        for (Map.Entry<ContentType, String> entry : expectedValues.entrySet()) {
            ContentResponse detailResponse =
                    createContentResponse(
                            entry.getKey()
                    );

            ContentListItemResponse listResponse =
                    createListItemResponse(
                            entry.getKey()
                    );

            JsonNode detailJson =
                    objectMapper.readTree(
                            objectMapper.writeValueAsString(
                                    detailResponse
                            )
                    );

            JsonNode listJson =
                    objectMapper.readTree(
                            objectMapper.writeValueAsString(
                                    listResponse
                            )
                    );

            assertThat(
                    detailJson.get("type").asText()
            ).isEqualTo(
                    entry.getValue()
            );

            assertThat(
                    listJson.get("type").asText()
            ).isEqualTo(
                    entry.getValue()
            );
        }
    }

    @Test
    void keepsRecommendationContentTypeAsEnumName() throws Exception {
        RecommendationItem recommendation =
                new RecommendationItem(
                        UUID.randomUUID(),
                        "테스트 추천",
                        null,
                        ContentType.TV_SERIES,
                        List.of(),
                        0.9,
                        8.5,
                        100.0,
                        1000L,
                        "추천 이유"
                );

        JsonNode json =
                objectMapper.readTree(
                        objectMapper.writeValueAsString(
                                recommendation
                        )
                );

        assertThat(
                json.get("type").asText()
        ).isEqualTo(
                "TV_SERIES"
        );
    }

    private ContentResponse createContentResponse(
            ContentType contentType
    ) {
        return new ContentResponse(
                UUID.randomUUID(),
                contentType,
                "테스트 콘텐츠",
                "테스트 설명",
                null,
                ExternalSource.MANUAL,
                null,
                LocalDate.of(
                        2026,
                        10,
                        5
                ),
                null,
                null,
                null,
                LocalDateTime.of(
                        2026,
                        10,
                        5,
                        12,
                        0
                ),
                LocalDateTime.of(
                        2026,
                        10,
                        5,
                        12,
                        0
                )
        );
    }

    private ContentListItemResponse createListItemResponse(
            ContentType contentType
    ) {
        return new ContentListItemResponse(
                UUID.randomUUID(),
                contentType,
                "테스트 콘텐츠",
                "테스트 설명",
                null,
                List.of(),
                0.0,
                0L,
                0L
        );
    }
}
