package com.mopl.reindexcli;

import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.infrastructure.ai.config.AiAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

@Slf4j
@RequiredArgsConstructor
public class ContentReindexCliRunner implements ApplicationRunner, ExitCodeGenerator {

    private final ContentSearchIndexer contentSearchIndexer;
    private final ContentEmbeddingIndexer contentEmbeddingIndexer;
    private final AiAvailability aiAvailability;
    private final ObjectProvider<DltReplayService> dltReplayServiceProvider;

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

        if (mode == ReindexMode.EMBEDDING_BACKFILL) {
            try {
                contentEmbeddingIndexer.validateBackfillConfiguration();
            } catch (ContentEmbeddingIndexer.BackfillConfigurationException exception) {
                log.error("Embedding Backfill 설정 오류: {}", exception.getMessage());
                exitCode = 2;
                return;
            }
        }

        if (mode == ReindexMode.DLT_REPLAY) {
            runDltReplay(arguments);
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

    private void runDltReplay(ApplicationArguments arguments) {
        try {
            String topic = requiredOption(arguments, "mopl.reindex.dlt.topic");
            int partition = Integer.parseInt(requiredOption(arguments, "mopl.reindex.dlt.partition"));
            long offset = Long.parseLong(requiredOption(arguments, "mopl.reindex.dlt.offset"));
            DltReplayService service = dltReplayServiceProvider.getIfAvailable();
            if (service == null) {
                throw new IllegalArgumentException(
                        "DLT_REPLAY에는 spring.kafka.bootstrap-servers 설정이 필요합니다."
                );
            }
            DltReplayService.ReplayResult result = service.replay(topic, partition, offset);
            log.info(
                    "DLT 재처리 완료. sent={}, sourceTopic={}, contentId={}, detail={}",
                    result.sent(), result.sourceTopic(), result.contentId(), result.detail()
            );
            exitCode = 0;
        } catch (IllegalArgumentException exception) {
            log.error("DLT 재처리 인자 또는 레코드 오류: {}", exception.getMessage());
            exitCode = 2;
        } catch (RuntimeException exception) {
            log.error("DLT 재처리에 실패했습니다.", exception);
            exitCode = 3;
        }
    }

    private String requiredOption(ApplicationArguments arguments, String name) {
        java.util.List<String> values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("필수 옵션이 필요합니다: --" + name);
        }
        return values.get(0);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
