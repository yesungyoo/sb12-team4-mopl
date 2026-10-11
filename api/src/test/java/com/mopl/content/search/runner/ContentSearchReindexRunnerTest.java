package com.mopl.content.search.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentSearchIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ContentSearchReindexRunnerTest {

    @Mock
    private ContentSearchIndexer contentSearchIndexer;

    @Mock
    private ContentEmbeddingIndexer contentEmbeddingIndexer;

    @Test
    void logsEarlyStopReasonAndCounts(CapturedOutput output) {
        when(contentSearchIndexer.reindexAll()).thenReturn(12L);
        when(contentEmbeddingIndexer.backfillMissing()).thenReturn(
                new ContentEmbeddingIndexer.BackfillResult(
                        3L,
                        2L,
                        4L,
                        5L,
                        ContentEmbeddingIndexer.BackfillStopReason.AUTHENTICATION_ERROR
                )
        );
        ContentSearchReindexRunner runner = new ContentSearchReindexRunner(
                contentSearchIndexer,
                contentEmbeddingIndexer
        );

        runner.run(null);

        assertThat(output.getOut())
                .contains("조기 중단")
                .contains("AUTHENTICATION_ERROR")
                .contains("성공=3")
                .contains("실패=2")
                .contains("건너뜀=4")
                .contains("미처리=5")
                .doesNotContain("Backfill 완료.");
        verify(contentSearchIndexer).reindexAll();
        verify(contentEmbeddingIndexer).backfillMissing();
    }
}
