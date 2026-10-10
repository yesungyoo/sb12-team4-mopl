package com.mopl.infrastructure.kafka;

import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;

@Slf4j
public class KafkaDeserializationFailureRecoverer implements ConsumerRecordRecoverer {

    @Override
    public void accept(
            ConsumerRecord<?, ?> record,
            Exception exception
    ) {
        DeserializationException deserializationException =
                findDeserializationException(exception);

        if (deserializationException == null) {
            throw new IllegalStateException(
                    "역직렬화 오류가 아닌 Kafka 처리 실패는 복구 처리하지 않습니다.",
                    exception
            );
        }

        Throwable cause = deserializationException.getCause();

        log.error(
                "Kafka 메시지 역직렬화 실패로 레코드를 복구 처리하고 offset을 진행합니다. "
                        + "topic={}, partition={}, offset={}, key={}, failedField={}, "
                        + "payloadBase64={}, headersBase64={}, causeType={}, causeMessage={}, "
                        + "offsetPolicy=RECOVER_AND_ADVANCE",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                deserializationException.isKey() ? "key" : "value",
                encode(deserializationException.getData()),
                encodeHeaders(record.headers()),
                cause == null
                        ? deserializationException.getClass().getName()
                        : cause.getClass().getName(),
                cause == null
                        ? deserializationException.getMessage()
                        : cause.getMessage()
        );
    }

    private DeserializationException findDeserializationException(
            Throwable exception
    ) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof DeserializationException deserializationException) {
                return deserializationException;
            }

            if (current == current.getCause()) {
                break;
            }

            current = current.getCause();
        }

        return null;
    }

    private String encode(byte[] value) {
        if (value == null) {
            return "null";
        }

        return Base64.getEncoder().encodeToString(value);
    }

    private String encodeHeaders(Headers headers) {
        return StreamSupport.stream(
                        headers.spliterator(),
                        false
                )
                .filter(header ->
                        !SerializationUtils.KEY_DESERIALIZER_EXCEPTION_HEADER
                                .equals(header.key())
                                && !SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER
                                .equals(header.key())
                )
                .map(header ->
                        header.key()
                                + "="
                                + encode(header.value())
                )
                .collect(
                        Collectors.joining(
                                ",",
                                "[",
                                "]"
                        )
                );
    }
}
