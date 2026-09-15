package com.mopl.batch.content.tasklet;

import com.mopl.batch.content.repository.ContentBulkRepository;
import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.sportsdb.client.SportsDbClient;
import com.mopl.batch.external.sportsdb.dto.SportsDbEventsResponse;
import com.mopl.batch.external.sportsdb.mapper.SportsDbContentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
public class SportsDbContentCollectionTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(SportsDbContentCollectionTasklet.class);

    private static final String COLLECTION_DATE_PARAMETER = "collectionDate";

    private final SportsDbClient sportsDbClient;
    private final SportsDbContentMapper sportsDbContentMapper;
    private final ContentBulkRepository contentBulkRepository;

    public SportsDbContentCollectionTasklet(
            SportsDbClient sportsDbClient,
            SportsDbContentMapper sportsDbContentMapper,
            ContentBulkRepository contentBulkRepository
    ) {
        this.sportsDbClient = sportsDbClient;
        this.sportsDbContentMapper = sportsDbContentMapper;
        this.contentBulkRepository = contentBulkRepository;
    }

    @Override
    public RepeatStatus execute(
            StepContribution contribution,
            ChunkContext chunkContext
    ) {
        LocalDate collectionDate = resolveCollectionDate(chunkContext);

        SportsDbEventsResponse response = sportsDbClient.getEventsByDate(collectionDate);

        if (response == null || response.events() == null || response.events().isEmpty()) {
            log.info(
                    "TheSportsDB 수집 대상 없음. collectionDate={}",
                    collectionDate
            );

            return RepeatStatus.FINISHED;
        }

        List<ExternalContentDto> contents = response.events().stream()
                .map(sportsDbContentMapper::fromEvent)
                .toList();

        int affectedRows = contentBulkRepository.upsertAll(contents);

        log.info(
                "TheSportsDB 콘텐츠 수집 완료. collectionDate={}, collectedCount={}, affectedRows={}",
                collectionDate,
                contents.size(),
                affectedRows
        );

        return RepeatStatus.FINISHED;
    }

    private LocalDate resolveCollectionDate(ChunkContext chunkContext) {
        Map<String, Object> jobParameters =
                chunkContext.getStepContext().getJobParameters();

        Object collectionDate = jobParameters.get(COLLECTION_DATE_PARAMETER);

        return LocalDate.parse(collectionDate.toString());
    }
}
