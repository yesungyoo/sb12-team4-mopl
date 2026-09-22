package com.mopl.content.search.document;

import com.mopl.core.domain.content.entity.Content;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Document(indexName = "contents")
public class ContentSearchDocument {

    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    @Field(type = FieldType.Keyword)
    private String type;

    @MultiField(
            mainField = @Field(type = FieldType.Text),
            otherFields = {
                    @InnerField(suffix = "keyword", type = FieldType.Keyword)
            }
    )
    private String title;

    @Field(type = FieldType.Text)
    private String description;

    @Field(type = FieldType.Keyword, index = false)
    private String thumbnailUrl;

    @Field(type = FieldType.Keyword)
    private String externalSource;

    @Field(type = FieldType.Keyword)
    private String externalId;

    @Field(type = FieldType.Date, format = DateFormat.date)
    private LocalDate releaseDate;

    @Field(type = FieldType.Double)
    private Double externalPopularity;

    @Field(type = FieldType.Double)
    private Double externalRating;

    @Field(type = FieldType.Long)
    private Long externalVoteCount;

    @Field(
            type = FieldType.Date,
            format = {},
            pattern = "uuuu-MM-dd'T'HH:mm:ss"
    )
    private LocalDateTime createdAt;

    @Field(
            type = FieldType.Dense_Vector,
            dims = EMBEDDING_DIMENSIONS,
            index = true,
            elementType = "float",
            knnSimilarity = KnnSimilarity.COSINE
    )
    private List<Float> embedding;

    public static ContentSearchDocument from(Content content, List<Double> embedding) {
        List<Float> floatEmbedding = embedding.stream()
                .map(Double::floatValue)
                .toList();

        return new ContentSearchDocument(
                content.getId().toString(),
                content.getType().name(),
                content.getTitle(),
                content.getDescription(),
                content.getThumbnailUrl(),
                content.getExternalSource().name(),
                content.getExternalId(),
                content.getReleaseDate(),
                content.getExternalPopularity() == null ? null : content.getExternalPopularity().doubleValue(),
                content.getExternalRating() == null ? null : content.getExternalRating().doubleValue(),
                content.getExternalVoteCount(),
                content.getCreatedAt(),
                floatEmbedding
        );
    }
}
