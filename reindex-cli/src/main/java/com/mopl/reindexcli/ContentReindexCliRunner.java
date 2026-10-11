package com.mopl.reindexcli;

import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.infrastructure.ai.config.AiAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

@Slf4j
@RequiredArgsConstructor
public class ContentReindexCliRunner implements ApplicationRunner, ExitCodeGenerator {

    private final ContentSearchIndexer contentSearchIndexer;
    private final ContentEmbeddingIndexer contentEmbeddingIndexer;
    private final AiAvailability aiAvailability;

    private int exitCode;

    @Override
    public void run(ApplicationArguments arguments) {
        ReindexMode mode;
        try {
            mode = ReindexMode.from(arguments);
        } catch (IllegalArgumentException exception) {
            log.error("재색인 CLI 인자 오류: {}", exception.getMessage());
            exitCode = 2;
            return;
        }

        if (mode == ReindexMode.EMBEDDING_BACKFILL
                && !aiAvailability.isAvailable()) {
            log.error(
                    "Embedding Backfill 설정 오류. AI 사용 불가 사유={}",
                    aiAvailability.unavailableReason()
            );
            exitCode = 2;
            return;
        }

        try {
            if (mode == ReindexMode.SYNC_DATA) {
                long indexedCount = contentSearchIndexer.reindexAll();
                log.info("SYNC_DATA 완료. indexedCount={}", indexedCount);
                exitCode = 0;
                return;
            }

            ContentEmbeddingIndexer.BackfillResult result =
                    contentEmbeddingIndexer.backfillMissing();
            log.info(
                    "EMBEDDING_BACKFILL 결과. 성공={}, 실패={}, 건너뜀={}, 미처리={}, 중단 사유={}",
                    result.succeeded(),
                    result.failed(),
                    result.skipped(),
                    result.unprocessed(),
                    result.stopReason()
            );
            exitCode = result.failed() > 0
                    || result.unprocessed() > 0
                    || result.stoppedEarly()
                    ? 1
                    : 0;
        } catch (RuntimeException exception) {
            log.error("재색인 CLI 인프라 처리에 실패했습니다. mode={}", mode, exception);
            exitCode = 3;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
