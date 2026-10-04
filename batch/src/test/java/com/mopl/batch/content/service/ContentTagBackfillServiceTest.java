package com.mopl.batch.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.batch.content.repository.ContentTagBackfillRepository;
import com.mopl.batch.content.repository.ContentTagBackfillRepository.Candidate;
import com.mopl.batch.external.common.dto.ExternalContentTagDto;
import com.mopl.batch.external.sportsdb.client.SportsDbClient;
import com.mopl.batch.external.sportsdb.dto.SportsDbEvent;
import com.mopl.batch.external.sportsdb.dto.SportsDbEventLookupResponse;
import com.mopl.batch.external.sportsdb.mapper.SportsDbContentMapper;
import com.mopl.batch.external.tmdb.client.TmdbClient;
import com.mopl.batch.external.tmdb.dto.TmdbContentDetails;
import com.mopl.batch.external.tmdb.mapper.TmdbContentMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentTagBackfillServiceTest {

    @Mock
    private ContentTagBackfillRepository backfillRepository;

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private SportsDbClient sportsDbClient;

    private ContentTagBackfillService backfillService;

    @BeforeEach
    void setUp() {
        backfillService = new ContentTagBackfillService(
                backfillRepository,
                tmdbClient,
                sportsDbClient,
                new TmdbContentMapper(),
                new SportsDbContentMapper()
        );
    }

    @Test
    void tmdbMovieAndTvTagsAreBackfilled() {
        Candidate movie = new Candidate(
                UUID.randomUUID(),
                "MOVIE",
                "TMDB",
                "100"
        );

        Candidate tv = new Candidate(
                UUID.randomUUID(),
                "TV_SERIES",
                "TMDB",
                "200"
        );

        when(backfillRepository.findUntaggedExternalContents())
                .thenReturn(List.of(movie, tv));

        when(tmdbClient.getMovieDetails("100"))
                .thenReturn(
                        new TmdbContentDetails(
                                List.of(
                                        new TmdbContentDetails.Genre(28),
                                        new TmdbContentDetails.Genre(12)
                                )
                        )
                );

        when(tmdbClient.getTvDetails("200"))
                .thenReturn(
                        new TmdbContentDetails(
                                List.of(
                                        new TmdbContentDetails.Genre(18)
                                )
                        )
                );

        List<ExternalContentTagDto> movieTags = List.of(
                new ExternalContentTagDto(
                        "GENRE",
                        "ACTION"
                ),
                new ExternalContentTagDto(
                        "GENRE",
                        "ADVENTURE"
                )
        );

        List<ExternalContentTagDto> tvTags = List.of(
                new ExternalContentTagDto(
                        "GENRE",
                        "DRAMA"
                )
        );

        when(backfillRepository.insertTags(
                movie.contentId(),
                movieTags
        )).thenReturn(2);

        when(backfillRepository.insertTags(
                tv.contentId(),
                tvTags
        )).thenReturn(1);

        ContentTagBackfillService.Result result =
                backfillService.backfill();

        assertThat(result.candidateCount())
                .isEqualTo(2);

        assertThat(result.taggedContents())
                .isEqualTo(2);

        assertThat(result.insertedTags())
                .isEqualTo(3);

        assertThat(result.failedContents())
                .isZero();

        verify(backfillRepository).insertTags(
                movie.contentId(),
                movieTags
        );

        verify(backfillRepository).insertTags(
                tv.contentId(),
                tvTags
        );
    }

    @Test
    void sportsDbTagsAreBackfilled() {
        Candidate sport = new Candidate(
                UUID.randomUUID(),
                "SPORT",
                "THESPORTSDB",
                "300"
        );

        SportsDbEvent event =
                mock(SportsDbEvent.class);

        when(event.id())
                .thenReturn("300");

        when(event.sport())
                .thenReturn("Soccer");

        when(event.leagueName())
                .thenReturn("English Premier League");

        when(backfillRepository.findUntaggedExternalContents())
                .thenReturn(List.of(sport));

        when(sportsDbClient.getEventById("300"))
                .thenReturn(
                        new SportsDbEventLookupResponse(
                                List.of(event)
                        )
                );

        List<ExternalContentTagDto> tags = List.of(
                new ExternalContentTagDto(
                        "SPORT",
                        "Soccer"
                ),
                new ExternalContentTagDto(
                        "LEAGUE",
                        "English Premier League"
                )
        );

        when(backfillRepository.insertTags(
                eq(sport.contentId()),
                eq(tags)
        )).thenReturn(2);

        ContentTagBackfillService.Result result =
                backfillService.backfill();

        assertThat(result.candidateCount())
                .isEqualTo(1);

        assertThat(result.taggedContents())
                .isEqualTo(1);

        assertThat(result.insertedTags())
                .isEqualTo(2);

        assertThat(result.failedContents())
                .isZero();
    }
}
