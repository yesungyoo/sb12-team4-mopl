package com.mopl.infrastructure.kafka;

import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;

@Slf4j
public class KafkaListenerFailureRecoverer
        implements ConsumerAwareRecordRecoverer {

    private final KafkaDeserializationFailureRecoverer
            deserializationFailureRecoverer;
    private final KafkaListenerEndpointRegistry listenerEndpointRegistry;

    public KafkaListenerFailureRecoverer(
            KafkaDeserializationFailureRecoverer
                    deserializationFailureRecoverer,
            KafkaListenerEndpointRegistry listenerEndpointRegistry
    ) {
        this.deserializationFailureRecoverer =
                deserializationFailureRecoverer;
        this.listenerEndpointRegistry = listenerEndpointRegistry;
    }

    @Override
    public void accept(
            ConsumerRecord<?, ?> record,
            Consumer<?, ?> consumer,
            Exception exception
    ) {
        if (findDeserializationException(exception) != null) {
            deserializationFailureRecoverer.accept(record, exception);
            return;
        }

        TopicPartition failedPartition = new TopicPartition(
                record.topic(),
                record.partition()
        );

        MessageListenerContainer listenerContainer =
                findAssignedContainer(failedPartition);

        if (listenerContainer == null) {
            log.error(
                    "Kafka Listener 실패 파티션을 담당하는 컨테이너를 "
                            + "찾을 수 없습니다. topic={}, partition={}, offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset()
            );
        } else {
            listenerContainer.pausePartition(failedPartition);
        }

        // 정상 반환하면 처리 완료로 간주되어 offset이 진행될 수 있으므로,
        // 컨테이너에 파티션 중지를 요청한 뒤 예외를 유지해 실패 레코드를 보존한다.

        log.error(
                "Kafka Listener 재시도를 모두 소진하여 실패 파티션을 "
                        + "일시 중지하고 offset을 유지합니다. "
                        + "topic={}, partition={}, offset={}, key={}, "
                        + "offsetPolicy=PAUSE_AND_KEEP_OFFSET, cause={}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                exception.getMessage(),
                exception
        );

        throw new IllegalStateException(
                "Kafka Listener 처리 실패로 파티션을 일시 중지했습니다.",
                exception
        );
    }

    private MessageListenerContainer findAssignedContainer(
            TopicPartition failedPartition
    ) {
        for (
                MessageListenerContainer listenerContainer
                : listenerEndpointRegistry.getListenerContainers()
        ) {
            Collection<TopicPartition> assignedPartitions =
                    listenerContainer.getAssignedPartitions();

            if (
                    assignedPartitions != null
                            && assignedPartitions.contains(failedPartition)
            ) {
                return listenerContainer;
            }
        }

        return null;
    }

    private DeserializationException findDeserializationException(
            Throwable exception
    ) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof DeserializationException found) {
                return found;
            }

            if (current == current.getCause()) {
                break;
            }

            current = current.getCause();
        }

        return null;
    }
}
