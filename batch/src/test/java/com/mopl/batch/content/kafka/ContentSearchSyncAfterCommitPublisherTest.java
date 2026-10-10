package com.mopl.batch.content.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ContentSearchSyncAfterCommitPublisherTest {

    @Mock
    private ObjectProvider<ContentSearchSyncKafkaProducer> producerProvider;

    @Mock
    private ContentSearchSyncKafkaProducer producer;

    private ContentSearchSyncAfterCommitPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ContentSearchSyncAfterCommitPublisher(
                producerProvider,
                1_000
        );
    }

    @AfterEach
    void cleanUpTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishesOnlyAfterTransactionCommit(CapturedOutput output) {
        beginTransactionSynchronization();
        UUID contentId = UUID.randomUUID();

        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(contentId, false)
        ));

        verifyNoInteractions(producerProvider, producer);

        TransactionSynchronization synchronization =
                onlyRegisteredSynchronization();
        when(producerProvider.getIfAvailable()).thenReturn(producer);
        when(producer.publish(contentId, false)).thenReturn(
                CompletableFuture.completedFuture(null)
        );

        synchronization.afterCommit();

        verify(producer).publish(contentId, false);
        assertThat(output)
                .contains("브로커 ACK 확인 성공")
                .contains(contentId.toString());
    }

    @Test
    void doesNotPublishWhenTransactionRollsBack() {
        beginTransactionSynchronization();

        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(UUID.randomUUID(), false)
        ));

        onlyRegisteredSynchronization().afterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK
        );

        verifyNoInteractions(producerProvider, producer);
    }

    @Test
    void keepsCommittedDbResultWhenKafkaIsNotConfigured(
            CapturedOutput output
    ) {
        beginTransactionSynchronization();
        when(producerProvider.getIfAvailable()).thenReturn(null);

        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(UUID.randomUUID(), false)
        ));

        assertThatCode(
                () -> onlyRegisteredSynchronization().afterCommit()
        ).doesNotThrowAnyException();

        verify(producer, never()).publish(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
        assertThat(output)
                .contains("spring.kafka.bootstrap-servers")
                .contains("발행하지 못했습니다");
    }

    @Test
    void doesNotPropagateUnexpectedProducerFailureAfterCommit(
            CapturedOutput output
    ) {
        beginTransactionSynchronization();
        UUID contentId = UUID.randomUUID();
        when(producerProvider.getIfAvailable()).thenReturn(producer);
        when(producer.publish(contentId, false)).thenThrow(
                new RuntimeException("예상하지 못한 Producer 오류")
        );

        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(contentId, false)
        ));

        assertThatCode(
                () -> onlyRegisteredSynchronization().afterCommit()
        ).doesNotThrowAnyException();
        assertThat(output)
                .contains("전송 실패")
                .contains(contentId.toString())
                .contains("예상하지 못한 Producer 오류");
    }

    @Test
    void logsAsynchronousSendFailureWithContentUuid(
            CapturedOutput output
    ) {
        beginTransactionSynchronization();
        UUID contentId = UUID.randomUUID();
        CompletableFuture<SendResult<String, Object>> failedFuture =
                CompletableFuture.failedFuture(
                        new RuntimeException("브로커 비동기 실패")
                );
        when(producerProvider.getIfAvailable()).thenReturn(producer);
        when(producer.publish(contentId, false)).thenReturn(failedFuture);

        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(contentId, false)
        ));

        assertThatCode(
                () -> onlyRegisteredSynchronization().afterCommit()
        ).doesNotThrowAnyException();
        assertThat(output)
                .contains("전송 실패")
                .contains(contentId.toString())
                .contains("브로커 비동기 실패");
    }

    @Test
    void stopsWaitingAfterConfiguredTimeout(CapturedOutput output) {
        publisher = new ContentSearchSyncAfterCommitPublisher(
                producerProvider,
                5
        );
        beginTransactionSynchronization();
        UUID contentId = UUID.randomUUID();
        CompletableFuture<SendResult<String, Object>> incompleteFuture =
                new CompletableFuture<>();
        when(producerProvider.getIfAvailable()).thenReturn(producer);
        when(producer.publish(contentId, false)).thenReturn(incompleteFuture);

        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(contentId, false)
        ));

        assertThatCode(
                () -> onlyRegisteredSynchronization().afterCommit()
        ).doesNotThrowAnyException();
        assertThat(output)
                .contains("확인 시간이 초과")
                .contains(contentId.toString())
                .contains("timeoutMillis=5");
        assertThat(incompleteFuture).isCancelled();
    }

    @Test
    void logsInterruptedWaitWithContentUuid(CapturedOutput output) {
        beginTransactionSynchronization();
        UUID contentId = UUID.randomUUID();
        CompletableFuture<SendResult<String, Object>> incompleteFuture =
                new CompletableFuture<>();
        when(producerProvider.getIfAvailable()).thenReturn(producer);
        when(producer.publish(contentId, false)).thenReturn(incompleteFuture);

        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(contentId, false)
        ));

        try {
            Thread.currentThread().interrupt();
            onlyRegisteredSynchronization().afterCommit();
        } finally {
            Thread.interrupted();
        }

        assertThat(output)
                .contains("전송 결과 확인이 중단")
                .contains(contentId.toString())
                .contains("대기 스레드 인터럽트");
        assertThat(incompleteFuture).isCancelled();
    }

    @Test
    void submitsEveryRequestBeforeWaitingForCompletion(
            CapturedOutput output
    ) {
        publisher = new ContentSearchSyncAfterCommitPublisher(
                producerProvider,
                5
        );
        beginTransactionSynchronization();
        UUID pendingContentId = UUID.randomUUID();
        UUID completedContentId = UUID.randomUUID();
        when(producerProvider.getIfAvailable()).thenReturn(producer);
        when(producer.publish(pendingContentId, false)).thenReturn(
                new CompletableFuture<>()
        );
        when(producer.publish(completedContentId, false)).thenReturn(
                CompletableFuture.completedFuture(null)
        );

        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(pendingContentId, false),
                new ContentSearchSyncTarget(completedContentId, false)
        ));

        onlyRegisteredSynchronization().afterCommit();

        verify(producer).publish(completedContentId, false);
        assertThat(output)
                .contains(pendingContentId.toString())
                .contains("확인 시간이 초과")
                .contains(completedContentId.toString())
                .contains("브로커 ACK 확인 성공");
    }

    @Test
    void blockedKafkaSendDoesNotDelayBatchBeyondCompletionTimeout(
            CapturedOutput output
    ) throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate =
                mock(KafkaTemplate.class);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch sendInterrupted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        when(kafkaTemplate.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        )).thenAnswer(invocation -> {
            sendStarted.countDown();
            try {
                releaseSend.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                sendInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Kafka 메타데이터 대기 중단",
                        exception
                );
            }
            return CompletableFuture.completedFuture(null);
        });

        ContentSearchSyncKafkaProducer blockingProducer =
                new ContentSearchSyncKafkaProducer(kafkaTemplate);
        publisher = new ContentSearchSyncAfterCommitPublisher(
                producerProvider,
                20
        );
        beginTransactionSynchronization();
        UUID contentId = UUID.randomUUID();
        when(producerProvider.getIfAvailable()).thenReturn(blockingProducer);
        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(contentId, false)
        ));

        long startedAt = System.nanoTime();
        try {
            onlyRegisteredSynchronization().afterCommit();
            assertThat(sendInterrupted.await(500, TimeUnit.MILLISECONDS))
                    .isTrue();
        } finally {
            releaseSend.countDown();
            blockingProducer.shutdownSendExecutor();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAt
        );

        assertThat(sendStarted.await(100, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(elapsedMillis).isLessThan(500L);
        assertThat(output)
                .contains("확인 시간이 초과")
                .contains(contentId.toString());
    }

    @Test
    void logsSuccessfulAndFailedResultsSeparately(
            CapturedOutput output
    ) {
        beginTransactionSynchronization();
        UUID successfulContentId = UUID.randomUUID();
        UUID failedContentId = UUID.randomUUID();
        when(producerProvider.getIfAvailable()).thenReturn(producer);
        when(producer.publish(successfulContentId, false)).thenReturn(
                CompletableFuture.completedFuture(null)
        );
        when(producer.publish(failedContentId, true)).thenReturn(
                CompletableFuture.failedFuture(
                        new RuntimeException("일부 전송 실패")
                )
        );

        publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(successfulContentId, false),
                new ContentSearchSyncTarget(failedContentId, true)
        ));

        assertThatCode(
                () -> onlyRegisteredSynchronization().afterCommit()
        ).doesNotThrowAnyException();
        assertThat(output)
                .contains("브로커 ACK 확인 성공")
                .contains(successfulContentId.toString())
                .contains("전송 실패")
                .contains(failedContentId.toString())
                .contains("일부 전송 실패");
    }

    @Test
    void rejectsSchedulingOutsideTransaction() {
        assertThatThrownBy(() -> publisher.publishAfterCommit(List.of(
                new ContentSearchSyncTarget(UUID.randomUUID(), false)
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("활성 DB 트랜잭션");
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private TransactionSynchronization onlyRegisteredSynchronization() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();

        assertThat(synchronizations).hasSize(1);
        return synchronizations.getFirst();
    }
}
