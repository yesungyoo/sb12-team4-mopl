package com.mopl.content.search.kafka;

import com.mopl.content.search.kafka.ContentSearchKafkaAutoResumeCoordinator.PauseReason;
import com.mopl.infrastructure.kafka.KafkaListenerFailureRecoverer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

@Slf4j
public class ContentSearchKafkaRecordRecoverer implements ConsumerAwareRecordRecoverer {

    private final DeadLetterPublishingRecoverer dltRecoverer;
    private final KafkaListenerFailureRecoverer pauseRecoverer;
    private final ContentSearchKafkaFailureClassifier classifier;
    private final ContentSearchKafkaAutoResumeCoordinator autoResumeCoordinator;
    private final String listenerName;

    public ContentSearchKafkaRecordRecoverer(
            DeadLetterPublishingRecoverer dltRecoverer,
            KafkaListenerFailureRecoverer pauseRecoverer,
            ContentSearchKafkaFailureClassifier classifier,
            ContentSearchKafkaAutoResumeCoordinator autoResumeCoordinator,
            String listenerName
    ) {
        this.dltRecoverer = dltRecoverer;
        this.pauseRecoverer = pauseRecoverer;
        this.classifier = classifier;
        this.autoResumeCoordinator = autoResumeCoordinator;
        this.listenerName = listenerName;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        ContentSearchKafkaFailureClassifier.FailureClassification classification =
                classifier.classifyWithDependency(exception);
        ContentSearchKafkaFailureClassifier.FailureType type = classification.failureType();
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        if (type == ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT
                || type == ContentSearchKafkaFailureClassifier.FailureType.COMMON_CONFIGURATION
                || type == ContentSearchKafkaFailureClassifier.FailureType.COMMON_AMBIGUOUS) {
            PauseReason pauseReason = type == ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT
                    ? PauseReason.COMMON_TRANSIENT
                    : type == ContentSearchKafkaFailureClassifier.FailureType.COMMON_CONFIGURATION
                    ? PauseReason.CONFIGURATION
                    : PauseReason.AMBIGUOUS;
            autoResumeCoordinator.recordFailure(
                    listenerName, partition, record.offset(), pauseReason, classification
            );
            log.error(
                    "공통 Kafka 처리 장애로 Listener 파티션을 일시 중지합니다. listener={}, type={}, topic={}, partition={}, offset={}",
                    listenerName, type, record.topic(), record.partition(), record.offset(), exception
            );
            pauseRecoverer.accept(record, consumer, exception);
            return;
        }

        autoResumeCoordinator.cancel(listenerName, partition);

        try {
            dltRecoverer.accept(record, consumer, exception);
            log.error(
                    "Kafka 레코드를 DLT로 격리했습니다. listener={}, type={}, topic={}, partition={}, offset={}",
                    listenerName, type, record.topic(), record.partition(), record.offset(), exception
            );
        } catch (RuntimeException dltFailure) {
            autoResumeCoordinator.recordFailure(
                    listenerName,
                    partition,
                    record.offset(),
                    PauseReason.DLT_PUBLISH_FAILURE,
                    classifier.classifyWithDependency(dltFailure)
            );
            log.error(
                    "DLT 발행 ACK를 확인하지 못해 원본 파티션을 중지하고 offset을 보존합니다. listener={}, topic={}, partition={}, offset={}",
                    listenerName, record.topic(), record.partition(), record.offset(), dltFailure
            );
            pauseRecoverer.pauseAndKeepOffset(record, consumer, dltFailure);
        }
    }
}
