package com.mopl.batch.content.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.mopl.batch.content.kafka.ContentSearchSyncAfterCommitPublisher;
import com.mopl.batch.content.kafka.ContentSearchSyncTarget;
import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.common.dto.ExternalContentTagDto;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class ContentBulkRepositoryIntegrationTest {

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
    private ContentBulkRepository contentBulkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ContentSearchSyncAfterCommitPublisher afterCommitPublisher;

    @Test
    void bulkUpsertResolvesGeneratedDatabaseUuidForSyncEvent() {
        ExternalContentDto content = createContent(
                "MOVIE",
                "UUID 조회 테스트",
                "TMDB",
                "bulk-uuid-test-001",
                100.0,
                4.5
        );

        contentBulkRepository.upsertAll(List.of(content));

        String storedId = jdbcTemplate.queryForObject(
                """
                        SELECT id
                        FROM contents
                        WHERE external_source = 'TMDB'
                            AND type = 'MOVIE'
                            AND external_id = 'bulk-uuid-test-001'
                        """,
                String.class
        );

        ArgumentCaptor<List<ContentSearchSyncTarget>> captor =
                syncTargetCaptor();
        verify(afterCommitPublisher).publishAfterCommit(captor.capture());

        assertThat(captor.getValue()).containsExactly(
                new ContentSearchSyncTarget(
                        UUID.fromString(storedId),
                        false
                )
        );
    }

    @Test
    void bulkUpsertInsertsNewContents() {
        ExternalContentDto movie = createContent(
                "MOVIE",
                "테스트 영화",
                "TMDB",
                "bulk-test-001",
                100.0,
                4.2
        );

        ExternalContentDto sport = createContent(
                "SPORT",
                "테스트 경기",
                "THESPORTSDB",
                "bulk-test-002",
                80.0,
                3.8
        );

        contentBulkRepository.upsertAll(
                List.of(movie, sport)
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM contents
                        WHERE external_id
                            IN (
                                'bulk-test-001',
                                'bulk-test-002'
                            )
                        """,
                Integer.class
        );

        assertThat(count).isEqualTo(2);
    }

    @Test
    void bulkUpsertStoresContentTags() {
        ExternalContentDto movie = createContent(
                "MOVIE",
                "태그 테스트 영화",
                "TMDB",
                "bulk-tag-test-001",
                100.0,
                4.5
        );

        contentBulkRepository.upsertAll(
                List.of(movie)
        );

        List<String> tags = jdbcTemplate.queryForList(
                """
                        SELECT CONCAT(
                            ct.tag,
                            ':',
                            ct.value
                        )
                        FROM content_tags ct
                        INNER JOIN contents c
                            ON c.id = ct.content_id
                        WHERE c.type = 'MOVIE'
                            AND c.external_source = 'TMDB'
                            AND c.external_id = 'bulk-tag-test-001'
                        ORDER BY ct.tag, ct.value
                        """,
                String.class
        );

        assertThat(tags)
                .containsExactly(
                        "GENRE:ACTION",
                        "GENRE:ADVENTURE"
                );
    }

    @Test
    void bulkUpsertReplacesExistingContentTags() {
        ExternalContentDto original =
                new ExternalContentDto(
                        "MOVIE",
                        "태그 변경 영화",
                        "테스트 설명",
                        "https://example.com/test.jpg",
                        "TMDB",
                        "bulk-tag-test-002",
                        LocalDate.of(
                                2026,
                                9,
                                12
                        ),
                        100.0,
                        4.5,
                        100L,
                        List.of(
                                new ExternalContentTagDto(
                                        "GENRE",
                                        "ACTION"
                                ),
                                new ExternalContentTagDto(
                                        "GENRE",
                                        "ADVENTURE"
                                )
                        )
                );

        contentBulkRepository.upsertAll(
                List.of(original)
        );

        ExternalContentDto updated =
                new ExternalContentDto(
                        "MOVIE",
                        "태그 변경 영화",
                        "테스트 설명",
                        "https://example.com/test.jpg",
                        "TMDB",
                        "bulk-tag-test-002",
                        LocalDate.of(
                                2026,
                                9,
                                12
                        ),
                        100.0,
                        4.5,
                        100L,
                        List.of(
                                new ExternalContentTagDto(
                                        "GENRE",
                                        "DRAMA"
                                )
                        )
                );

        contentBulkRepository.upsertAll(
                List.of(updated)
        );

        List<String> tags = jdbcTemplate.queryForList(
                """
                        SELECT ct.value
                        FROM content_tags ct
                        INNER JOIN contents c
                            ON c.id = ct.content_id
                        WHERE c.type = 'MOVIE'
                            AND c.external_source = 'TMDB'
                            AND c.external_id = 'bulk-tag-test-002'
                        ORDER BY ct.value
                        """,
                String.class
        );

        assertThat(tags)
                .containsExactly("DRAMA");
    }

    @Test
    void bulkUpsertUpdatesExistingContentWithoutCreatingDuplicate() {
        ExternalContentDto original = createContent(
                "MOVIE",
                "수정 전 제목",
                "TMDB",
                "bulk-test-003",
                50.0,
                3.0
        );

        contentBulkRepository.upsertAll(
                List.of(original)
        );

        String originalId = jdbcTemplate.queryForObject(
                """
                        SELECT id
                        FROM contents
                        WHERE external_source = 'TMDB'
                            AND type = 'MOVIE'
                            AND external_id = 'bulk-test-003'
                        """,
                String.class
        );

        clearInvocations(afterCommitPublisher);

        ExternalContentDto updated = createContent(
                "MOVIE",
                "수정 후 제목",
                "TMDB",
                "bulk-test-003",
                150.0,
                4.8
        );

        contentBulkRepository.upsertAll(
                List.of(updated)
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM contents
                        WHERE external_source = 'TMDB'
                            AND external_id = 'bulk-test-003'
                        """,
                Integer.class
        );

        String title = jdbcTemplate.queryForObject(
                """
                        SELECT title
                        FROM contents
                        WHERE external_source = 'TMDB'
                            AND external_id = 'bulk-test-003'
                        """,
                String.class
        );

        Double rating = jdbcTemplate.queryForObject(
                """
                        SELECT external_rating
                        FROM contents
                        WHERE external_source = 'TMDB'
                            AND external_id = 'bulk-test-003'
                        """,
                Double.class
        );

        assertThat(count).isEqualTo(1);
        assertThat(title).isEqualTo("수정 후 제목");
        assertThat(rating).isEqualTo(4.8);

        ArgumentCaptor<List<ContentSearchSyncTarget>> captor =
                syncTargetCaptor();
        verify(afterCommitPublisher).publishAfterCommit(captor.capture());
        assertThat(captor.getValue()).containsExactly(
                new ContentSearchSyncTarget(
                        UUID.fromString(originalId),
                        false
                )
        );
    }

    @Test
    void bulkUpsertDoesNotRestoreSoftDeletedContent() {
        ExternalContentDto content = createContent(
                "MOVIE",
                "삭제 테스트",
                "TMDB",
                "bulk-test-004",
                50.0,
                3.5
        );

        contentBulkRepository.upsertAll(
                List.of(content)
        );

        String contentId = jdbcTemplate.queryForObject(
                """
                        SELECT id
                        FROM contents
                        WHERE external_source = 'TMDB'
                            AND type = 'MOVIE'
                            AND external_id = 'bulk-test-004'
                        """,
                String.class
        );

        jdbcTemplate.update(
                """
                        UPDATE contents
                        SET deleted_at = NOW()
                        WHERE external_source = 'TMDB'
                            AND external_id = 'bulk-test-004'
                        """
        );

        clearInvocations(afterCommitPublisher);

        ExternalContentDto collectedAgain =
                createContent(
                        "MOVIE",
                        "재수집된 콘텐츠",
                        "TMDB",
                        "bulk-test-004",
                        100.0,
                        4.5
                );

        contentBulkRepository.upsertAll(
                List.of(collectedAgain)
        );

        Integer deletedCount =
                jdbcTemplate.queryForObject(
                        """
                                SELECT COUNT(*)
                                FROM contents
                                WHERE external_source = 'TMDB'
                                    AND external_id = 'bulk-test-004'
                                    AND deleted_at IS NOT NULL
                                """,
                        Integer.class
                );

        assertThat(deletedCount)
                .isEqualTo(1);

        ArgumentCaptor<List<ContentSearchSyncTarget>> captor =
                syncTargetCaptor();
        verify(afterCommitPublisher).publishAfterCommit(captor.capture());
        assertThat(captor.getValue()).containsExactly(
                new ContentSearchSyncTarget(
                        UUID.fromString(contentId),
                        true
                )
        );
    }

    @Test
    void upsertAllReturnsZeroWhenContentsAreEmpty() {
        int result =
                contentBulkRepository.upsertAll(
                        List.of()
                );

        assertThat(result).isZero();
    }

    @Test
    void bulkUpsertKeepsMovieAndTvWithSameExternalIdSeparate() {
        ExternalContentDto movie = createContent(
                "MOVIE",
                "동일 ID 영화",
                "TMDB",
                "same-id-001",
                100.0,
                4.5
        );

        ExternalContentDto tv = createContent(
                "TV_SERIES",
                "동일 ID TV",
                "TMDB",
                "same-id-001",
                90.0,
                4.2
        );

        contentBulkRepository.upsertAll(
                List.of(movie, tv)
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM contents
                        WHERE external_source = 'TMDB'
                            AND external_id = 'same-id-001'
                        """,
                Integer.class
        );

        assertThat(count).isEqualTo(2);
    }

    private ExternalContentDto createContent(
            String type,
            String title,
            String externalSource,
            String externalId,
            Double popularity,
            Double rating
    ) {
        List<ExternalContentTagDto> tags =
                "SPORT".equals(type)
                        ? List.of(
                        new ExternalContentTagDto(
                                "SPORT",
                                "Soccer"
                        ),
                        new ExternalContentTagDto(
                                "LEAGUE",
                                "English Premier League"
                        )
                )
                        : List.of(
                        new ExternalContentTagDto(
                                "GENRE",
                                "ACTION"
                        ),
                        new ExternalContentTagDto(
                                "GENRE",
                                "ADVENTURE"
                        )
                );

        return new ExternalContentDto(
                type,
                title,
                "테스트 설명",
                "https://example.com/test.jpg",
                externalSource,
                externalId,
                LocalDate.of(
                        2026,
                        9,
                        12
                ),
                popularity,
                rating,
                100L,
                tags
        );
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<ContentSearchSyncTarget>> syncTargetCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
