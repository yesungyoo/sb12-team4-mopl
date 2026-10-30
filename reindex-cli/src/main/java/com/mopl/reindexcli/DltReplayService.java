package com.mopl.reindexcli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.common.kafka.ContentEmbeddingKafkaEvent;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class DltReplayService {

    private static final String TYPE_ID_HEADER = "__TypeId__";
    private static final String SEARCH_TYPE_ID = "contentSearchSync";
    private static final String EMBEDDING_TYPE_ID = "contentEmbedding";

    private final ConsumerFactory<byte[], byte[]> consumerFactory;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ContentRepository contentRepository;
    private final ObjectMapper objectMapper;
    private final long readTimeoutMs;
    private final long ackTimeoutMs;

    public DltReplayService(
            @Qualifier("dltReplayConsumerFactory") ConsumerFactory<byte[], byte[]> consumerFactory,
            @Qualifier("kafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            ContentRepository contentRepository,
            ObjectMapper objectMapper,
            @Value("${mopl.kafka.dlt.replay.read-timeout-ms:30000}") long readTimeoutMs,
            @Value("${mopl.kafka.dlt.replay.ack-timeout-ms:15000}") long ackTimeoutMs
    ) {
        if (readTimeoutMs <= 0 || ackTimeoutMs <= 0) {
            throw new IllegalArgumentException("DLT 재처리 대기 시간은 양수여야 합니다.");
        }
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.contentRepository = contentRepository;
        this.objectMapper = objectMapper;
        this.readTimeoutMs = readTimeoutMs;
        this.ackTimeoutMs = ackTimeoutMs;
    }

    public ReplayResult replay(String dltTopic, int partition, long offset) {
        if (partition < 0 || offset < 0) {
            throw new IllegalArgumentException("DLT partition과 offset은 음수일 수 없습니다.");
        }
        ReplayTarget target = target(dltTopic);
        TopicPartition topicPartition = new TopicPartition(dltTopic, partition);

        try (Consumer<byte[], byte[]> consumer = consumerFactory.createConsumer()) {
            consumer.assign(List.of(topicPartition));
            ConsumerRecord<byte[], byte[]> record;
            try {
                consumer.seek(topicPartition, offset);
                record = findSelectedRecord(consumer, topicPartition, offset);
            } catch (OffsetOutOfRangeException exception) {
                throw new IllegalArgumentException(
                        "선택한 DLT offset이 Topic의 보존 범위를 벗어났습니다: " + offset,
                        exception
                );
            }
            rejectUnresolvedDeserialization(record);
            return replayRecord(target, record);
        }
    }

    private ConsumerRecord<byte[], byte[]> findSelectedRecord(
            Consumer<byte[], byte[]> consumer,
            TopicPartition topicPartition,
            long offset
    ) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs);
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(Math.min(1000, readTimeoutMs)));
            for (ConsumerRecord<byte[], byte[]> record : records.records(topicPartition)) {
                if (record.offset() == offset) {
                    return record;
                }
                if (record.offset() > offset) {
                    throw new IllegalArgumentException("선택한 DLT offset 레코드를 찾을 수 없습니다: " + offset);
                }
            }
        }
        throw new IllegalArgumentException("선택한 DLT offset에 레코드가 없습니다: " + offset);
    }

    private void rejectUnresolvedDeserialization(ConsumerRecord<byte[], byte[]> record) {
        String exceptionType = headerValue(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
        String causeType = headerValue(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        String keyExceptionType = headerValue(record, KafkaHeaders.DLT_KEY_EXCEPTION_FQCN);
        if (isDeserializationFailure(exceptionType)
                || isDeserializationFailure(causeType)
                || isDeserializationFailure(keyExceptionType)) {
            throw new IllegalArgumentException(
                    "역직렬화 실패 원인이 해결되지 않은 raw-byte DLT 레코드는 자동 재발행할 수 없습니다. "
                            + "Type ID/직렬화 원인을 먼저 확인하세요."
            );
        }
    }

    private String headerValue(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null
                ? ""
                : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean isDeserializationFailure(String exceptionType) {
        return exceptionType.contains("DeserializationException");
    }

    private ReplayResult replayRecord(ReplayTarget target, ConsumerRecord<byte[], byte[]> record) {
        if (record.value() == null || record.key() == null) {
            throw new IllegalArgumentException("DLT 레코드의 Key 또는 Value가 비어 있습니다.");
        }
        verifyTypeId(record, target.typeId());
        String key = new String(record.key(), java.nio.charset.StandardCharsets.UTF_8);

        try {
            if (target.embedding()) {
                ContentEmbeddingKafkaEvent event = objectMapper.readValue(
                        record.value(), ContentEmbeddingKafkaEvent.class
                );
                verifyKey(key, event.contentId());
                if (contentRepository.findByIdAndDeletedAtIsNull(event.contentId()).isEmpty()) {
                    return ReplayResult.skipped("콘텐츠가 삭제되었거나 존재하지 않습니다.");
                }
                send(target.sourceTopic(), event.contentId().toString(), event);
                return ReplayResult.sent(target.sourceTopic(), event.contentId());
            }

            ContentSearchSyncKafkaEvent event = objectMapper.readValue(
                    record.value(), ContentSearchSyncKafkaEvent.class
            );
            verifyKey(key, event.contentId());
            boolean deleted = contentRepository
                    .findByIdAndDeletedAtIsNull(event.contentId())
                    .isEmpty();
            ContentSearchSyncKafkaEvent currentStateEvent = new ContentSearchSyncKafkaEvent(
                    event.contentId(), deleted
            );
            send(target.sourceTopic(), event.contentId().toString(), currentStateEvent);
            return ReplayResult.sent(target.sourceTopic(), event.contentId());
        } catch (IOException exception) {
            throw new IllegalArgumentException("DLT JSON을 원본 이벤트로 안전하게 해석할 수 없습니다.", exception);
        }
    }

    private void verifyTypeId(ConsumerRecord<byte[], byte[]> record, String expectedTypeId) {
        Header typeHeader = record.headers().lastHeader(TYPE_ID_HEADER);
        if (typeHeader == null) {
            throw new IllegalArgumentException("DLT 레코드에 Kafka Type ID가 없습니다.");
        }
        String actual = new String(typeHeader.value(), java.nio.charset.StandardCharsets.UTF_8);
        String legacySearchType = "com.mopl.content.search.kafka.event.ContentSearchSyncKafkaEvent";
        if (!expectedTypeId.equals(actual)
                && !(SEARCH_TYPE_ID.equals(expectedTypeId) && legacySearchType.equals(actual))) {
            throw new IllegalArgumentException("DLT Type ID가 허용된 이벤트와 다릅니다: " + actual);
        }
    }

    private void verifyKey(String key, UUID contentId) {
        if (contentId == null || !contentId.toString().equals(key)) {
            throw new IllegalArgumentException("DLT Key와 이벤트 contentId가 일치하지 않습니다.");
        }
    }

    private void send(String topic, String key, Object event) {
        try {
            kafkaTemplate.send(topic, key, event).get(ackTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("원본 Topic 재발행 ACK 대기가 중단되었습니다.", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("원본 Topic 재발행 ACK 대기 시간이 초과되었습니다.", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("원본 Topic 재발행 ACK를 확인하지 못했습니다.", exception);
        }
    }

    private ReplayTarget target(String dltTopic) {
        if (ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT.equals(dltTopic)) {
            return new ReplayTarget(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC, SEARCH_TYPE_ID, false);
        }
        if (ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT.equals(dltTopic)) {
            return new ReplayTarget(ContentSearchKafkaTopics.CONTENT_EMBEDDING, EMBEDDING_TYPE_ID, true);
        }
        throw new IllegalArgumentException("허용되지 않은 DLT Topic입니다: " + dltTopic);
    }

    private record ReplayTarget(String sourceTopic, String typeId, boolean embedding) {
    }

    public record ReplayResult(boolean sent, String sourceTopic, UUID contentId, String detail) {
        static ReplayResult sent(String topic, UUID id) {
            return new ReplayResult(true, topic, id, "ACK 확인 완료");
        }

        static ReplayResult skipped(String detail) {
            return new ReplayResult(false, null, null, detail);
        }
    }
}
