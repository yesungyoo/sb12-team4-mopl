package com.mopl.batch.content.service;

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
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentTagBackfillService {

    private static final String TMDB = "TMDB";
    private static final String THE_SPORTS_DB = "THESPORTSDB";

    private static final String MOVIE = "MOVIE";
    private static final String TV_SERIES = "TV_SERIES";
    private static final String SPORT = "SPORT";

    private final ContentTagBackfillRepository backfillRepository;
    private final TmdbClient tmdbClient;
    private final SportsDbClient sportsDbClient;
    private final TmdbContentMapper tmdbContentMapper;
    private final SportsDbContentMapper sportsDbContentMapper;

    public Result backfill() {
        List<Candidate> candidates =
                backfillRepository.findUntaggedExternalContents();

        int taggedContents = 0;
        int insertedTags = 0;
        int skippedContents = 0;
        int failedContents = 0;

        for (Candidate candidate : candidates) {
            try {
                List<ExternalContentTagDto> tags =
                        findTags(candidate);

                if (tags.isEmpty()) {
                    skippedContents++;
                    continue;
                }

                int inserted =
                        backfillRepository.insertTags(
                                candidate.contentId(),
                                tags
                        );

                if (inserted > 0) {
                    taggedContents++;
                    insertedTags += inserted;
                } else {
                    skippedContents++;
                }
            } catch (Exception exception) {
                failedContents++;

                log.warn(
                        "콘텐츠 태그 backfill 실패. contentId={}, type={}, source={}, externalId={}",
                        candidate.contentId(),
                        candidate.type(),
                        candidate.externalSource(),
                        candidate.externalId(),
                        exception
                );
            }
        }

        return new Result(
                candidates.size(),
                taggedContents,
                insertedTags,
                skippedContents,
                failedContents
        );
    }

    private List<ExternalContentTagDto> findTags(
            Candidate candidate
    ) {
        return switch (candidate.externalSource()) {
            case TMDB -> findTmdbTags(candidate);
            case THE_SPORTS_DB -> findSportsDbTags(candidate);
            default -> List.of();
        };
    }

    private List<ExternalContentTagDto> findTmdbTags(
            Candidate candidate
    ) {
        TmdbContentDetails details = switch (candidate.type()) {
            case MOVIE ->
                    tmdbClient.getMovieDetails(
                            candidate.externalId()
                    );

            case TV_SERIES ->
                    tmdbClient.getTvDetails(
                            candidate.externalId()
                    );

            default -> null;
        };

        if (details == null
                || details.genres() == null
                || details.genres().isEmpty()) {
            return List.of();
        }

        List<Integer> genreIds = details.genres()
                .stream()
                .filter(Objects::nonNull)
                .map(TmdbContentDetails.Genre::id)
                .filter(Objects::nonNull)
                .toList();

        return tmdbContentMapper.createGenreTags(
                genreIds
        );
    }

    private List<ExternalContentTagDto> findSportsDbTags(
            Candidate candidate
    ) {
        if (!SPORT.equals(candidate.type())) {
            return List.of();
        }

        SportsDbEventLookupResponse response =
                sportsDbClient.getEventById(
                        candidate.externalId()
                );

        if (response == null
                || response.events() == null
                || response.events().isEmpty()) {
            return List.of();
        }

        SportsDbEvent event = response.events()
                .stream()
                .filter(Objects::nonNull)
                .filter(candidateEvent ->
                        candidate.externalId().equals(
                                candidateEvent.id()
                        )
                )
                .findFirst()
                .orElse(response.events().getFirst());

        return sportsDbContentMapper
                .fromEvent(event)
                .tags();
    }

    public record Result(
            int candidateCount,
            int taggedContents,
            int insertedTags,
            int skippedContents,
            int failedContents
    ) {
    }
}
