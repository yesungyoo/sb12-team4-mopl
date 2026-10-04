package com.mopl.batch.content.runner;

import com.mopl.batch.content.service.ContentTagBackfillService;
import com.mopl.batch.content.service.ContentTagBackfillService.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "mopl.content-tag-backfill",
        name = "enabled",
        havingValue = "true"
)
public class ContentTagBackfillRunner implements ApplicationRunner {

    private final ContentTagBackfillService contentTagBackfillService;

    @Override
    public void run(ApplicationArguments args) {
        Result result =
                contentTagBackfillService.backfill();

        log.info(
                "콘텐츠 태그 backfill 완료. candidates={}, taggedContents={}, insertedTags={}, skippedContents={}, failedContents={}",
                result.candidateCount(),
                result.taggedContents(),
                result.insertedTags(),
                result.skippedContents(),
                result.failedContents()
        );
    }
}
