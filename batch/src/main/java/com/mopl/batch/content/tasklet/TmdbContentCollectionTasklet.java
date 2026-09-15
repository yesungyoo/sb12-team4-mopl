package com.mopl.batch.content.tasklet;

import com.mopl.batch.content.repository.ContentBulkRepository;
import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.tmdb.client.TmdbClient;
import com.mopl.batch.external.tmdb.dto.TmdbMovieResponse;
import com.mopl.batch.external.tmdb.dto.TmdbTvResponse;
import com.mopl.batch.external.tmdb.mapper.TmdbContentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TmdbContentCollectionTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(TmdbContentCollectionTasklet.class);

    private static final int DEFAULT_PAGE = 1;

    private final TmdbClient tmdbClient;
    private final TmdbContentMapper tmdbContentMapper;
    private final ContentBulkRepository contentBulkRepository;

    public TmdbContentCollectionTasklet(
            TmdbClient tmdbClient,
            TmdbContentMapper tmdbContentMapper,
            ContentBulkRepository contentBulkRepository
    ) {
        this.tmdbClient = tmdbClient;
        this.tmdbContentMapper = tmdbContentMapper;
        this.contentBulkRepository = contentBulkRepository;
    }

    @Override
    public RepeatStatus execute(
            StepContribution contribution,
            ChunkContext chunkContext
    ) {
        List<ExternalContentDto> contents = new ArrayList<>();

        collectMovies(contents);
        collectTvShows(contents);

        int affectedRows = contentBulkRepository.upsertAll(contents);

        log.info(
                "TMDB 콘텐츠 수집 완료. collectedCount={}, affectedRows={}",
                contents.size(),
                affectedRows
        );

        return RepeatStatus.FINISHED;
    }

    private void collectMovies(List<ExternalContentDto> contents) {
        TmdbMovieResponse response = tmdbClient.getPopularMovies(DEFAULT_PAGE);

        if (response == null || response.results() == null) {
            return;
        }

        response.results().stream()
                .map(tmdbContentMapper::fromMovie)
                .forEach(contents::add);
    }

    private void collectTvShows(List<ExternalContentDto> contents) {
        TmdbTvResponse response = tmdbClient.getPopularTvShows(DEFAULT_PAGE);

        if (response == null || response.results() == null) {
            return;
        }

        response.results().stream()
                .map(tmdbContentMapper::fromTv)
                .forEach(contents::add);
    }
}
