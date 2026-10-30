package com.mopl.reindexcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class ContentReindexCliRunnerTest {

    @Mock
    private ContentSearchIndexer contentSearchIndexer;

    @Mock
    private ContentEmbeddingIndexer contentEmbeddingIndexer;

    @Mock
    private AiAvailability aiAvailability;

    @Test
    void syncDataRunsOnlySearchIndexerAndExitsSuccessfully() {
        when(contentSearchIndexer.reindexAll()).thenReturn(14L);
        ContentReindexCliRunner runner = createRunner();

        runner.run(arguments("SYNC_DATA"));

        assertThat(runner.getExitCode()).isZero();
        verify(contentSearchIndexer).reindexAll();
        verify(contentEmbeddingIndexer, never()).backfillMissing();
        verify(contentEmbeddingIndexer, never()).validateBackfillConfiguration();
    }

    @Test
    void embeddingBackfillRejectsInvalidThresholdBeforeAiPreflightOrProcessing() {
        doThrow(new ContentEmbeddingIndexer.BackfillConfigurationException(
                "잘못된 Backfill 전용 임계치"
        )).when(contentEmbeddingIndexer).validateBackfillConfiguration();
        ContentReindexCliRunner runner = createRunner();

        runner.run(arguments("EMBEDDING_BACKFILL"));

        assertThat(runner.getExitCode()).isEqualTo(2);
        verify(aiAvailability, never()).isAvailable();
        verify(contentEmbeddingIndexer, never()).backfillMissing();
    }

    @Test
    void embeddingBackfillSucceedsWhenThereAreNoFailuresOrUnprocessedTargets() {
        when(aiAvailability.isAvailable()).thenReturn(true);
        when(contentEmbeddingIndexer.backfillMissing()).thenReturn(
                new ContentEmbeddingIndexer.BackfillResult(
                        10L,
                        0L,
                        2L,
                        0L,
                        ContentEmbeddingIndexer.BackfillStopReason.NONE
                )
        );
        ContentReindexCliRunner runner = createRunner();

        runner.run(arguments("EMBEDDING_BACKFILL"));

        assertThat(runner.getExitCode()).isZero();
        verify(contentEmbeddingIndexer).backfillMissing();
        verify(contentSearchIndexer, never()).reindexAll();
    }

    @Test
    void embeddingBackfillReturnsFailureForFailedTargets() {
        when(aiAvailability.isAvailable()).thenReturn(true);
        when(contentEmbeddingIndexer.backfillMissing()).thenReturn(result(
                1L,
                0L,
                ContentEmbeddingIndexer.BackfillStopReason.NONE
        ));
        ContentReindexCliRunner runner = createRunner();

        runner.run(arguments("EMBEDDING_BACKFILL"));

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void embeddingBackfillReturnsFailureForUnprocessedTargets() {
        when(aiAvailability.isAvailable()).thenReturn(true);
        when(contentEmbeddingIndexer.backfillMissing()).thenReturn(result(
                0L,
                1L,
                ContentEmbeddingIndexer.BackfillStopReason.NONE
        ));
        ContentReindexCliRunner runner = createRunner();

        runner.run(arguments("EMBEDDING_BACKFILL"));

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void embeddingBackfillReturnsFailureWhenSomeTargetsFailAndRemainUnprocessed() {
        when(aiAvailability.isAvailable()).thenReturn(true);
        when(contentEmbeddingIndexer.backfillMissing()).thenReturn(result(
                2L,
                3L,
                ContentEmbeddingIndexer.BackfillStopReason.NONE
        ));
        ContentReindexCliRunner runner = createRunner();

        runner.run(arguments("EMBEDDING_BACKFILL"));

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ContentEmbeddingIndexer.BackfillStopReason.class, names = {
            "AUTHENTICATION_ERROR",
            "CONSECUTIVE_FAILURE_LIMIT",
            "CONSECUTIVE_ELASTICSEARCH_FAILURE_LIMIT"
    })
    void embeddingBackfillReturnsFailureForEarlyStopReason(
            ContentEmbeddingIndexer.BackfillStopReason stopReason
    ) {
        when(aiAvailability.isAvailable()).thenReturn(true);
        when(contentEmbeddingIndexer.backfillMissing()).thenReturn(
                result(0L, 0L, stopReason)
        );
        ContentReindexCliRunner runner = createRunner();

        runner.run(arguments("EMBEDDING_BACKFILL"));

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(AiAvailability.UnavailableReason.class)
    void embeddingModeReturnsConfigurationErrorWhenAiIsUnavailable(
            AiAvailability.UnavailableReason unavailableReason
    ) {
        when(aiAvailability.isAvailable()).thenReturn(false);
        when(aiAvailability.unavailableReason()).thenReturn(unavailableReason);
        ContentReindexCliRunner runner = createRunner();

        runner.run(arguments("EMBEDDING_BACKFILL"));

        assertThat(runner.getExitCode()).isEqualTo(2);
        verify(contentEmbeddingIndexer, never()).backfillMissing();
    }

    @Test
    void invalidModeReturnsConfigurationExitCode() {
        ContentReindexCliRunner runner = createRunner();

        runner.run(new DefaultApplicationArguments("--mopl.reindex.mode=OTHER"));

        assertThat(runner.getExitCode()).isEqualTo(2);
    }

    @Test
    void infrastructureFailureReturnsInfrastructureExitCode() {
        when(contentSearchIndexer.reindexAll())
                .thenThrow(new IllegalStateException("Elasticsearch unavailable"));
        ContentReindexCliRunner runner = createRunner();

        runner.run(arguments("SYNC_DATA"));

        assertThat(runner.getExitCode()).isEqualTo(3);
    }

    @Test
    void dltReplayInvalidRecordReturnsInputErrorExitCode() {
        ObjectProvider<DltReplayService> provider = mock(ObjectProvider.class);
        DltReplayService replayService = mock(DltReplayService.class);
        when(provider.getIfAvailable()).thenReturn(replayService);
        doThrow(new IllegalArgumentException("선택한 DLT offset에 레코드가 없습니다"))
                .when(replayService).replay(anyString(), anyInt(), anyLong());
        ContentReindexCliRunner runner = createRunner(provider);

        runner.run(dltReplayArguments());

        assertThat(runner.getExitCode()).isEqualTo(2);
    }

    @Test
    void dltReplayInfrastructureFailureReturnsInfrastructureExitCode() {
        ObjectProvider<DltReplayService> provider = mock(ObjectProvider.class);
        DltReplayService replayService = mock(DltReplayService.class);
        when(provider.getIfAvailable()).thenReturn(replayService);
        doThrow(new IllegalStateException("원본 Topic 재발행 ACK 실패"))
                .when(replayService).replay(anyString(), anyInt(), anyLong());
        ContentReindexCliRunner runner = createRunner(provider);

        runner.run(dltReplayArguments());

        assertThat(runner.getExitCode()).isEqualTo(3);
    }

    private ContentReindexCliRunner createRunner() {
        return createRunner(mock(ObjectProvider.class));
    }

    private ContentReindexCliRunner createRunner(ObjectProvider<DltReplayService> provider) {
        return new ContentReindexCliRunner(
                contentSearchIndexer,
                contentEmbeddingIndexer,
                aiAvailability,
                provider
        );
    }

    private DefaultApplicationArguments dltReplayArguments() {
        return new DefaultApplicationArguments(
                "--mopl.reindex.mode=DLT_REPLAY",
                "--mopl.reindex.dlt.topic=" + ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT,
                "--mopl.reindex.dlt.partition=0",
                "--mopl.reindex.dlt.offset=11"
        );
    }

    private ContentEmbeddingIndexer.BackfillResult result(
            long failed,
            long unprocessed,
            ContentEmbeddingIndexer.BackfillStopReason stopReason
    ) {
        return new ContentEmbeddingIndexer.BackfillResult(
                0L,
                failed,
                0L,
                unprocessed,
                stopReason
        );
    }

    private DefaultApplicationArguments arguments(String mode) {
        return new DefaultApplicationArguments(
                "--mopl.reindex.mode=" + mode
        );
    }
}
