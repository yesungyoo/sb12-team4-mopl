package com.mopl.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.serializer.DeserializationException;

class KafkaDeserializationFailureRecovererTest {

    private final KafkaDeserializationFailureRecoverer recoverer =
            new KafkaDeserializationFailureRecoverer();

    @Test
    void logsRecordIdentityAndRecoverablePayloadForDeserializationFailure() {
        byte[] payload = "invalid-json".getBytes(StandardCharsets.UTF_8);

        RecordHeaders headers = new RecordHeaders();
        headers.add(
                new RecordHeader(
                        "__TypeId__",
                        "contentSearchSync".getBytes(StandardCharsets.UTF_8)
                )
        );

        ConsumerRecord<String, Object> record =
                new ConsumerRecord<>(
                        "content-search-sync",
                        2,
                        17L,
                        "content-key",
                        null
                );

        headers.forEach(record.headers()::add);

        DeserializationException exception =
                new DeserializationException(
                        "failed to deserialize",
                        payload,
                        false,
                        new IllegalArgumentException("broken json")
                );

        Logger logger =
                (Logger) LoggerFactory.getLogger(
                        KafkaDeserializationFailureRecoverer.class
                );

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            recoverer.accept(
                    record,
                    exception
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(1);

        String message = appender.list.getFirst().getFormattedMessage();

        assertThat(message)
                .contains(
                        "topic=content-search-sync",
                        "partition=2",
                        "offset=17",
                        "key=content-key",
                        "failedField=value",
                        "payloadBase64="
                                + Base64.getEncoder().encodeToString(payload),
                        "causeType=java.lang.IllegalArgumentException",
                        "causeMessage=broken json",
                        "offsetPolicy=RECOVER_AND_ADVANCE"
                );
    }

    @Test
    void doesNotRecoverNonDeserializationFailure() {
        ConsumerRecord<String, Object> record =
                new ConsumerRecord<>(
                        "content-search-sync",
                        0,
                        3L,
                        "content-key",
                        new Object()
                );

        RuntimeException processingFailure =
                new RuntimeException("Elasticsearch unavailable");

        assertThatThrownBy(
                () -> recoverer.accept(
                        record,
                        processingFailure
                )
        ).isInstanceOf(IllegalStateException.class)
                .hasCause(processingFailure);
    }
}
