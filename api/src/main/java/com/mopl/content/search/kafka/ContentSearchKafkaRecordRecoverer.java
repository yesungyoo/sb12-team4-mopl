package com.mopl.content.search.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import com.mopl.infrastructure.kafka.KafkaListenerFailureRecoverer;

@Slf4j
public class ContentSearchKafkaRecordRecoverer implements ConsumerAwareRecordRecoverer {

    private final DeadLetterPublishingRecoverer dltRecoverer;
    private final KafkaListenerFailureRecoverer pauseRecoverer;
    private final ContentSearchKafkaFailureClassifier classifier;
    private final String listenerName;

    public ContentSearchKafkaRecordRecoverer(
            DeadLetterPublishingRecoverer dltRecoverer,
            KafkaListenerFailureRecoverer pauseRecoverer,
            ContentSearchKafkaFailureClassifier classifier,
            String listenerName
    ) {
        this.dltRecoverer = dltRecoverer;
        this.pauseRecoverer = pauseRecoverer;
        this.classifier = classifier;
        this.listenerName = listenerName;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        ContentSearchKafkaFailureClassifier.FailureType type = classifier.classify(exception);
        if (type == ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT
                || type == ContentSearchKafkaFailureClassifier.FailureType.COMMON_CONFIGURATION
                || type == ContentSearchKafkaFailureClassifier.FailureType.COMMON_AMBIGUOUS) {
            log.error(
                    "공통 Kafka 처리 장애로 Listener 파티션을 일시 중지합니다. listener={}, type={}, topic={}, partition={}, offset={}",
                    listenerName, type, record.topic(), record.partition(), record.offset(), exception
            );
            pauseRecoverer.accept(record, consumer, exception);
            return;
        }

        try {
            dltRecoverer.accept(record, consumer, exception);
            log.error(
                    "Kafka 레코드를 DLT로 격리했습니다. listener={}, type={}, topic={}, partition={}, offset={}",
                    listenerName, type, record.topic(), record.partition(), record.offset(), exception
            );
        } catch (RuntimeException dltFailure) {
            log.error(
                    "DLT 발행 ACK를 확인하지 못해 원본 파티션을 중지하고 offset을 보존합니다. listener={}, topic={}, partition={}, offset={}",
                    listenerName, record.topic(), record.partition(), record.offset(), dltFailure
            );
            pauseRecoverer.pauseAndKeepOffset(record, consumer, dltFailure);
        }
    }
}
