package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.mopl.content.dto.ContentListResponse;
import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.content.search.service.ContentSearchService;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;

@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379"
})
@Testcontainers
public class ContentSearchIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mopl")
            .withUsername("mopl")
            .withPassword("mopl")
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.18.8"))
                    .withEnv("xpack.security.enabled", "false")
                    .withStartupTimeout(Duration.ofMinutes(5));

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private ContentSearchRepository contentSearchRepository;

    @Autowired
    private ContentSearchIndexer contentSearchIndexer;

    @Autowired
    private ContentSearchService contentSearchService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.elasticsearch.uris", ELASTICSEARCH::getHttpHostAddress);
    }

    @BeforeEach
    void setUp() {
        contentSearchRepository.deleteAll();
        contentRepository.deleteAllInBatch();
    }

    @Test
    void searchByKeywordTypeAndDateRange() {
        Content spiderMan = createContent(
                ContentType.MOVIE,
                "Spider Hero",
                "A hero protects the city",
                LocalDate.of(2026, 7, 29),
                new BigDecimal("700.0"),
                new BigDecimal("8.0")
        );

        Content drama = createContent(
                ContentType.TV_SERIES,
                "Hero Drama",
                "A long running television drama",
                LocalDate.of(2025, 3, 15),
                new BigDecimal("300.0"),
                new BigDecimal("7.0")
        );

        contentRepository.saveAll(List.of(spiderMan, drama));

        long indexedCount = contentSearchIndexer.reindexAll();

        ContentSearchCondition condition = new ContentSearchCondition(
                "Hero",
                ContentType.MOVIE,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)
        );

        ContentListResponse response = contentSearchService.search(
                condition,
                PageRequest.of(0, 10)
        );

        assertThat(indexedCount).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.contents()).hasSize(1);
        assertThat(response.contents().getFirst().title())
                .isEqualTo("Spider Hero");
    }

    @Test
    void searchSortsByExternalPopularityDescending() {
        Content first = createContent(
                ContentType.MOVIE,
                "First Movie",
                "movie",
                LocalDate.of(2026, 1, 1),
                new BigDecimal("100.0"),
                new BigDecimal("7.0")
        );

        Content second = createContent(
                ContentType.MOVIE,
                "Second Movie",
                "movie",
                LocalDate.of(2026, 1, 2),
                new BigDecimal("500.0"),
                new BigDecimal("8.0")
        );

        contentRepository.saveAll(List.of(first, second));

        contentSearchIndexer.reindexAll();

        ContentSearchCondition condition = new ContentSearchCondition(
                null,
                ContentType.MOVIE,
                null,
                null
        );

        ContentListResponse response = contentSearchService.search(
                condition,
                PageRequest.of(0, 10, Sort.by(
                        Sort.Direction.DESC, "externalPopularity"
                ))
        );

        assertThat(response.contents()).hasSize(2);
        assertThat(response.contents().get(0).title())
                .isEqualTo("Second Movie");
        assertThat(response.contents().get(1).title())
                .isEqualTo("First Movie");
    }

    private Content createContent(
            ContentType type,
            String title,
            String description,
            LocalDate releaseDate,
            BigDecimal popularity,
            BigDecimal rating
    ) {
        return new Content(
                type,
                title,
                description,
                null,
                ExternalSource.MANUAL,
                null,
                releaseDate,
                popularity,
                rating,
                100L
        );
    }
}
