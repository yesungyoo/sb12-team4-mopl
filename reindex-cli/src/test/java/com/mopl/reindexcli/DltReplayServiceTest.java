package com.mopl.reindexcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.common.kafka.ContentEmbeddingKafkaEvent;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import com.mopl.core.domain.content.entity.Content;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class DltReplayServiceTest {

    private final ConsumerFactory<byte[], byte[]> consumerFactory = mock(ConsumerFactory.class);
    private final Consumer<byte[], byte[]> consumer = mock(Consumer.class);
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ContentRepository contentRepository = mock(ContentRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DltReplayService service = new DltReplayService(
            consumerFactory,
            kafkaTemplate,
            contentRepository,
            objectMapper,
            1000,
            1000
    );

    @BeforeEach
    void setUp() {
        when(consumerFactory.createConsumer()).thenReturn(consumer);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void searchReplayUsesCurrentDatabaseDeletionStateAndDoesNotCommitDltOffset() throws Exception {
        UUID contentId = UUID.randomUUID();
        ConsumerRecord<byte[], byte[]> record = record(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT,
                0,
                7,
                contentId,
                objectMapper.writeValueAsBytes(new ContentSearchSyncKafkaEvent(contentId, false)),
                "contentSearchSync"
        );
        stubSelectedRecord(record);
        when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.empty());

        DltReplayService.ReplayResult result = service.replay(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0, 7
        );

        assertThat(result.sent()).isTrue();
        verify(kafkaTemplate).send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                contentId.toString(),
                new ContentSearchSyncKafkaEvent(contentId, true)
        );
        verify(consumer).seek(new TopicPartition(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0), 7L);
        verify(consumer, never()).commitSync();
    }

    @Test
    void embeddingReplaySkipsDeletedContentWithoutPublishing() throws Exception {
        UUID contentId = UUID.randomUUID();
        ConsumerRecord<byte[], byte[]> record = record(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT,
                0,
                4,
                contentId,
                objectMapper.writeValueAsBytes(new ContentEmbeddingKafkaEvent(contentId)),
                "contentEmbedding"
        );
        stubSelectedRecord(record);
        when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.empty());

        DltReplayService.ReplayResult result = service.replay(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT, 0, 4
        );

        assertThat(result.sent()).isFalse();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void activeEmbeddingReplayPublishesOnlyTheSelectedContentId() throws Exception {
        UUID contentId = UUID.randomUUID();
        ConsumerRecord<byte[], byte[]> record = record(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT,
                1,
                12,
                contentId,
                objectMapper.writeValueAsBytes(new ContentEmbeddingKafkaEvent(contentId)),
                "contentEmbedding"
        );
        stubSelectedRecord(record);
        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.of(mock(Content.class)));

        DltReplayService.ReplayResult result = service.replay(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT, 1, 12
        );

        assertThat(result.sent()).isTrue();
        verify(kafkaTemplate).send(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                contentId.toString(),
                new ContentEmbeddingKafkaEvent(contentId)
        );
    }

    @Test
    void unresolvedRawDeserializationRecordIsRejectedBeforeRepublish() throws Exception {
        UUID contentId = UUID.randomUUID();
        ConsumerRecord<byte[], byte[]> record = record(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT,
                0,
                8,
                contentId,
                objectMapper.writeValueAsBytes(new ContentSearchSyncKafkaEvent(contentId, false)),
                "contentSearchSync"
        );
        record.headers().add(new RecordHeader(
                org.springframework.kafka.support.KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "org.springframework.kafka.support.serializer.DeserializationException"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));
        stubSelectedRecord(record);

        assertThatThrownBy(() -> service.replay(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0, 8
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("역직렬화 실패 원인");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void unresolvedRawKeyDeserializationRecordIsRejectedBeforeRepublish() throws Exception {
        UUID contentId = UUID.randomUUID();
        ConsumerRecord<byte[], byte[]> record = record(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT,
                0,
                10,
                contentId,
                objectMapper.writeValueAsBytes(new ContentSearchSyncKafkaEvent(contentId, false)),
                "contentSearchSync"
        );
        record.headers().add(new RecordHeader(
                org.springframework.kafka.support.KafkaHeaders.DLT_KEY_EXCEPTION_FQCN,
                "org.springframework.kafka.support.serializer.DeserializationException"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));
        stubSelectedRecord(record);

        assertThatThrownBy(() -> service.replay(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0, 10
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("역직렬화 실패 원인");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void replayRejectsOffsetBeyondLatestRecordWithoutRepublishing() {
        DltReplayService fastService = new DltReplayService(
                consumerFactory, kafkaTemplate, contentRepository, objectMapper, 1, 1000
        );
        when(consumer.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of()));

        assertThatThrownBy(() -> fastService.replay(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0, 13
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("선택한 DLT offset에 레코드가 없습니다");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void replayMapsKafkaOffsetOutOfRangeToInvalidInputWithoutRepublishing() {
        TopicPartition topicPartition = new TopicPartition(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0
        );
        when(consumer.poll(any(Duration.class))).thenThrow(new OffsetOutOfRangeException(
                Map.of(topicPartition, 13L)
        ));

        assertThatThrownBy(() -> service.replay(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0, 13
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("보존 범위");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void replayDoesNotReportSuccessWhenSourceAckFails() throws Exception {
        UUID contentId = UUID.randomUUID();
        ConsumerRecord<byte[], byte[]> record = record(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT,
                0,
                9,
                contentId,
                objectMapper.writeValueAsBytes(new ContentSearchSyncKafkaEvent(contentId, false)),
                "contentSearchSync"
        );
        stubSelectedRecord(record);
        when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.empty());
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        assertThatThrownBy(() -> service.replay(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0, 9
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACK");
    }

    @Test
    void replayWaitsForSourceAcknowledgementBeforeReturningSuccess() throws Exception {
        UUID contentId = UUID.randomUUID();
        ConsumerRecord<byte[], byte[]> record = record(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT,
                0,
                11,
                contentId,
                objectMapper.writeValueAsBytes(new ContentSearchSyncKafkaEvent(contentId, false)),
                "contentSearchSync"
        );
        stubSelectedRecord(record);
        when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.empty());

        CountDownLatch sendStarted = new CountDownLatch(1);
        CompletableFuture<SendResult<String, Object>> ack = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
            sendStarted.countDown();
            return ack;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DltReplayService.ReplayResult> replay = executor.submit(() -> service.replay(
                    ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0, 11
            ));

            assertThat(sendStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(replay.isDone()).isFalse();
            ack.complete(null);
            assertThat(replay.get(1, TimeUnit.SECONDS).sent()).isTrue();
        } finally {
            ack.complete(null);
            executor.shutdownNow();
        }
    }

    private ConsumerRecord<byte[], byte[]> record(
            String topic,
            int partition,
            long offset,
            UUID contentId,
            byte[] value,
            String typeId
    ) {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                topic,
                partition,
                offset,
                contentId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                value
        );
        record.headers().add(new RecordHeader(
                "__TypeId__", typeId.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));
        return record;
    }

    private void stubSelectedRecord(ConsumerRecord<byte[], byte[]> record) {
        TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
        when(consumer.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(
                Map.of(topicPartition, List.of(record))
        ));
    }
}
