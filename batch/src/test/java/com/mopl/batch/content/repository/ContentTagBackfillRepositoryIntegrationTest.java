package com.mopl.batch.content.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.batch.content.repository.ContentTagBackfillRepository.Candidate;
import com.mopl.batch.external.common.dto.ExternalContentTagDto;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.flyway.enabled=true"
})
@ActiveProfiles("local")
@Transactional
class ContentTagBackfillRepositoryIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("mopl")
                    .withUsername("mopl")
                    .withPassword("test")
                    .withStartupTimeout(
                            Duration.ofMinutes(5)
                    );

    @DynamicPropertySource
    static void configureDatasource(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                MYSQL::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                MYSQL::getUsername
        );

        registry.add(
                "spring.datasource.password",
                MYSQL::getPassword
        );
    }

    @Autowired
    private ContentTagBackfillRepository backfillRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void insertsBackfillTagsIdempotently() {
        jdbcTemplate.update(
                """
                        INSERT INTO contents (
                            id,
                            type,
                            title,
                            description,
                            thumbnail_url,
                            external_source,
                            external_id,
                            release_date,
                            external_popularity,
                            external_rating,
                            external_vote_count,
                            created_at,
                            updated_at,
                            deleted_at
                        )
                        VALUES (
                            UUID(),
                            'MOVIE',
                            'Backfill Test Movie',
                            NULL,
                            NULL,
                            'TMDB',
                            'backfill-test-001',
                            NULL,
                            NULL,
                            NULL,
                            NULL,
                            NOW(),
                            NOW(),
                            NULL
                        )
                        """
        );

        List<Candidate> candidates =
                backfillRepository.findUntaggedExternalContents();

        Candidate candidate = candidates.stream()
                .filter(item ->
                        "backfill-test-001".equals(
                                item.externalId()
                        )
                )
                .findFirst()
                .orElseThrow();

        List<ExternalContentTagDto> tags = List.of(
                new ExternalContentTagDto(
                        "GENRE",
                        "ACTION"
                ),
                new ExternalContentTagDto(
                        "GENRE",
                        "ADVENTURE"
                )
        );

        int firstInserted =
                backfillRepository.insertTags(
                        candidate.contentId(),
                        tags
                );

        int secondInserted =
                backfillRepository.insertTags(
                        candidate.contentId(),
                        tags
                );

        Integer storedTagCount =
                jdbcTemplate.queryForObject(
                        """
                                SELECT COUNT(*)
                                FROM content_tags
                                WHERE content_id = ?
                                """,
                        Integer.class,
                        candidate.contentId().toString()
                );

        assertThat(firstInserted)
                .isEqualTo(2);

        assertThat(secondInserted)
                .isZero();

        assertThat(storedTagCount)
                .isEqualTo(2);

        assertThat(
                backfillRepository.findUntaggedExternalContents()
        ).noneMatch(item ->
                item.contentId().equals(
                        candidate.contentId()
                )
        );
    }
}
