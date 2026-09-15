package com.mopl.batch.content.job;

import com.mopl.batch.external.sportsdb.client.SportsDbClient;
import com.mopl.batch.external.sportsdb.dto.SportsDbEvent;
import com.mopl.batch.external.sportsdb.dto.SportsDbEventsResponse;
import com.mopl.batch.external.tmdb.client.TmdbClient;
import com.mopl.batch.external.tmdb.dto.TmdbMovie;
import com.mopl.batch.external.tmdb.dto.TmdbMovieResponse;
import com.mopl.batch.external.tmdb.dto.TmdbTv;
import com.mopl.batch.external.tmdb.dto.TmdbTvResponse;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.flyway.enabled=true"
})
@ActiveProfiles("local")
class ContentCollectionJobIntegrationTest {

    private static final LocalDate COLLECTION_DATE = LocalDate.of(2026, 9, 15);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mopl")
            .withUsername("mopl")
            .withPassword("test")
            .withStartupTimeout(Duration.ofMinutes(5));

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("contentCollectionJob")
    private Job contentCollectionJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TmdbClient tmdbClient;

    @MockitoBean
    private SportsDbClient sportsDbClient;

    @Test
    void contentCollectionJobCompletes() throws Exception {
        when(tmdbClient.getPopularMovies(1))
                .thenReturn(new TmdbMovieResponse(1, List.of(), 1, 0));

        when(tmdbClient.getPopularTvShows(1))
                .thenReturn(new TmdbTvResponse(1, List.of(), 1, 0));

        when(sportsDbClient.getEventsByDate(COLLECTION_DATE))
                .thenReturn(new SportsDbEventsResponse(List.of()));

        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .addString("collectionDate", COLLECTION_DATE.toString())
                .toJobParameters();

        JobExecution execution = jobLauncher.run(contentCollectionJob, jobParameters);

        assertThat(execution.getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void contentCollectionJobStoresExternalContents() throws Exception {
        TmdbMovie movie = new TmdbMovie(
                900001L,
                "배치 테스트 영화",
                "영화 설명",
                LocalDate.of(2026, 9, 1),
                "/movie.jpg",
                "/movie-backdrop.jpg",
                List.of(28, 12),
                100.0,
                4.5,
                1000L
        );

        TmdbTv tv = new TmdbTv(
                900002L,
                "배치 테스트 TV",
                "TV 설명",
                LocalDate.of(2026, 9, 2),
                "/tv.jpg",
                "/tv-backdrop.jpg",
                List.of(18),
                90.0,
                4.3,
                800L
        );

        SportsDbEvent event = new SportsDbEvent(
                "batch-sports-001",
                "배치 테스트 경기",
                "Soccer",
                "Test League",
                "Home Team",
                "Away Team",
                COLLECTION_DATE,
                "2026-09-15T19:00:00",
                "Not Started",
                "https://example.com/sports.jpg"
        );

        when(tmdbClient.getPopularMovies(1))
                .thenReturn(new TmdbMovieResponse(1, List.of(movie), 1, 1));

        when(tmdbClient.getPopularTvShows(1))
                .thenReturn(new TmdbTvResponse(1, List.of(tv), 1, 1));

        when(sportsDbClient.getEventsByDate(COLLECTION_DATE))
                .thenReturn(new SportsDbEventsResponse(List.of(event)));

        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .addString("collectionDate", COLLECTION_DATE.toString())
                .toJobParameters();

        JobExecution execution = jobLauncher.run(contentCollectionJob, jobParameters);

        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM contents
                        WHERE (external_source = 'TMDB'
                            AND external_id IN ('900001', '900002'))
                            OR (external_source = 'THESPORTSDB'
                            AND external_id = 'batch-sports-001')
                        """,
                Integer.class
        );

        assertThat(execution.getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void contentCollectionJobFailsWhenTmdbCollectionFails() throws Exception {
        when(tmdbClient.getPopularMovies(1))
                .thenThrow(new IllegalStateException("TMDB API 호출 실패"));

        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .addString("collectionDate", COLLECTION_DATE.toString())
                .toJobParameters();

        JobExecution execution =
                jobLauncher.run(contentCollectionJob, jobParameters);

        assertThat(execution.getStatus())
                .isEqualTo(BatchStatus.FAILED);
    }

    @Test
    void contentCollectionJobRejectsMissingCollectionDate() {
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        assertThatThrownBy(
                () -> jobLauncher.run(contentCollectionJob, jobParameters)
        )
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("collectionDate");
    }

    @Test
    void contentCollectionJobRejectsInvalidCollectionDate() {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("collectionDate", "2026-99-99")
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        assertThatThrownBy(
                () -> jobLauncher.run(contentCollectionJob, jobParameters)
        )
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }
}
