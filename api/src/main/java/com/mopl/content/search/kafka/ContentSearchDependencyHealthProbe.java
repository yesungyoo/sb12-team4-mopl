package com.mopl.content.search.kafka;

import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.Dependency;

/** A read-only health check used before resuming a paused content Kafka partition. */
public interface ContentSearchDependencyHealthProbe {

    Dependency dependency();

    void checkHealthy();

    class HealthCheckFailedException extends RuntimeException {

        private final String reasonCode;

        public HealthCheckFailedException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
