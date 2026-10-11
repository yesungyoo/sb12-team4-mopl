package com.mopl.content.search.runner;

import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentSearchIndexer;
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
        prefix = "mopl.elasticsearch",
        name = "reindex-on-startup",
        havingValue = "true"
)
public class ContentSearchReindexRunner implements ApplicationRunner {

    private final ContentSearchIndexer contentSearchIndexer;
    private final ContentEmbeddingIndexer contentEmbeddingIndexer;

    @Override
    public void run(ApplicationArguments args) {
        long indexedCount = contentSearchIndexer.reindexAll();

        log.info("Elasticsearch 콘텐츠 전체 재색인 완료. indexedCount={}", indexedCount);

        ContentEmbeddingIndexer.BackfillResult backfillResult =
                contentEmbeddingIndexer.backfillMissing();

        if (backfillResult.stoppedEarly()) {
            log.warn(
                    "Elasticsearch 콘텐츠 Embedding Backfill 조기 중단. 이유={}, 성공={}, 실패={}, 건너뜀={}, 미처리={}",
                    backfillResult.stopReason(),
                    backfillResult.succeeded(),
                    backfillResult.failed(),
                    backfillResult.skipped(),
                    backfillResult.unprocessed()
            );
            return;
        }

        log.info(
                "Elasticsearch 콘텐츠 embedding Backfill 완료. 성공={}, 실패={}, 건너뜀={}, 미처리={}",
                backfillResult.succeeded(),
                backfillResult.failed(),
                backfillResult.skipped(),
                backfillResult.unprocessed()
        );
    }
}
