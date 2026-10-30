package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.infrastructure.ai.exception.AiClientException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.elasticsearch.NoSuchIndexException;
import org.springframework.data.elasticsearch.RestStatusException;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.client.HttpClientErrorException;

class ContentSearchKafkaFailureClassifierTest {

    private final ContentSearchKafkaFailureClassifier classifier =
            new ContentSearchKafkaFailureClassifier();

    @Test
    void distinguishesOpenAiRateLimitFromQuotaAndUnknown429() {
        assertThat(classifier.classify(aiFailure(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
        assertThat(classifier.classify(aiFailure(429, "{\"error\":{\"code\":\"insufficient_quota\"}}")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_CONFIGURATION);
        assertThat(classifier.classify(aiFailure(429, "{\"error\":{\"message\":\"unknown\"}}")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_AMBIGUOUS);
    }

    @Test
    void keepsElasticsearch429PausedRegardlessOfResponseBody() {
        RestStatusException failure = new RestStatusException(429, "rate_limit_exceeded");

        assertThat(classifier.classify(failure))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_AMBIGUOUS);
        assertThat(classifier.shouldSkipRetry(failure)).isTrue();
        assertThat(classifier.classify(aiFailure(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
    }

    @Test
    void classifiesSpecificDatabaseResourceFailuresAsCommonWithoutBroadeningDataAccessFailures() {
        assertThat(classifier.classify(new DataAccessResourceFailureException("resource unavailable")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
        assertThat(classifier.classify(new CannotGetJdbcConnectionException(
                "Hikari connection acquisition timed out", new SQLTransientConnectionException("pool timeout")
        ))).isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
        assertThat(classifier.classify(new SQLTransientConnectionException("connection timeout")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
        assertThat(classifier.classify(new SQLRecoverableException("connection lost")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
        assertThat(classifier.classify(new SQLException("communications failure", "08006")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
        assertThat(classifier.classify(new DataIntegrityViolationException("duplicate content")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.RETRY_THEN_DLT);
    }

    @Test
    void doesNotTreatHttpStatusAloneAsRecordPermanent() {
        assertThat(classifier.classify(aiFailure(400, "{\"error\":{\"type\":\"invalid_request_error\"}}")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_AMBIGUOUS);
        assertThat(classifier.classify(new FakeElasticsearchException(
                400,
                "mapper_parsing_exception: failed to parse field [rating]"
        )))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.RECORD_PERMANENT);
        assertThat(classifier.classify(new FakeElasticsearchException(
                400,
                "mapper_parsing_exception: incompatible index mapping"
        )))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_AMBIGUOUS);
        assertThat(classifier.classify(new FakeElasticsearchExceptionWithoutStatus()))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_AMBIGUOUS);
    }

    @Test
    void classifiesAuthenticationAndNetworkCausesAsCommonFailures() {
        assertThat(classifier.classify(aiFailure(401, "{}")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_CONFIGURATION);
        assertThat(classifier.classify(new java.net.ConnectException("refused")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
    }

    @Test
    void classifiesSpringDataElasticsearchExceptionsUsingStatusAndResponseBody() {
        assertThat(classifier.classify(new UncategorizedElasticsearchException(
                "Elasticsearch rejected document",
                400,
                "mapper_parsing_exception: failed to parse field [rating]",
                null
        ))).isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.RECORD_PERMANENT);
        assertThat(classifier.classify(new RestStatusException(
                400,
                "mapper_parsing_exception: incompatible index mapping"
        ))).isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_AMBIGUOUS);
        assertThat(classifier.classify(new RestStatusException(503, "cluster unavailable")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
        assertThat(classifier.classify(new NoSuchIndexException("content-search")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_CONFIGURATION);
        assertThat(classifier.classify(new UncategorizedElasticsearchException(
                "Elasticsearch response without status", null, null,
                new RestStatusException(503, "cluster unavailable")
        ))).isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT);
    }

    @Test
    void identifiesOnlyExplicitElasticsearchAndMysqlTransientDependenciesForAutoResume() {
        assertThat(classifier.classifyWithDependency(new RestStatusException(503, "cluster unavailable")))
                .isEqualTo(new ContentSearchKafkaFailureClassifier.FailureClassification(
                        ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT,
                        ContentSearchKafkaFailureClassifier.Dependency.ELASTICSEARCH
                ));
        assertThat(classifier.classifyWithDependency(new CannotGetJdbcConnectionException(
                "connection unavailable", new SQLTransientConnectionException("pool timeout")
        ))).isEqualTo(new ContentSearchKafkaFailureClassifier.FailureClassification(
                ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT,
                ContentSearchKafkaFailureClassifier.Dependency.MYSQL
        ));
        assertThat(classifier.classifyWithDependency(aiFailure(503, "service unavailable")).dependency())
                .isEqualTo(ContentSearchKafkaFailureClassifier.Dependency.OPENAI);
        assertThat(classifier.classifyWithDependency(new java.net.ConnectException("connection refused"))
                .dependency()).isEqualTo(ContentSearchKafkaFailureClassifier.Dependency.UNKNOWN);
        assertThat(classifier.classifyWithDependency(new DataAccessResourceFailureException("resource unavailable"))
                .dependency()).isEqualTo(ContentSearchKafkaFailureClassifier.Dependency.UNKNOWN);
        assertThat(classifier.classifyWithDependency(new DataAccessResourceFailureException(
                "Elasticsearch connection failed",
                new org.springframework.web.client.ResourceAccessException("connection refused")
        ))).isEqualTo(new ContentSearchKafkaFailureClassifier.FailureClassification(
                ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT,
                ContentSearchKafkaFailureClassifier.Dependency.ELASTICSEARCH
        ));
        assertThat(classifier.classifyWithDependency(new DataAccessResourceFailureException(
                "Elasticsearch connection failed", new java.net.ConnectException("connection refused")
        )).dependency()).isEqualTo(ContentSearchKafkaFailureClassifier.Dependency.ELASTICSEARCH);
    }

    @Test
    void identifiesSpringDataElasticsearchClosedConnectionWithoutGuessingStandaloneCause() {
        org.apache.http.ConnectionClosedException closedConnection =
                new org.apache.http.ConnectionClosedException("Connection is closed");
        DataAccessResourceFailureException elasticsearchFailure = new DataAccessResourceFailureException(
                "Connection is closed", new RuntimeException("Connection is closed", closedConnection));

        assertThat(classifier.classifyWithDependency(elasticsearchFailure))
                .isEqualTo(new ContentSearchKafkaFailureClassifier.FailureClassification(
                        ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT,
                        ContentSearchKafkaFailureClassifier.Dependency.ELASTICSEARCH
                ));
        assertThat(classifier.classifyWithDependency(closedConnection))
                .isEqualTo(new ContentSearchKafkaFailureClassifier.FailureClassification(
                        ContentSearchKafkaFailureClassifier.FailureType.RETRY_THEN_DLT,
                        ContentSearchKafkaFailureClassifier.Dependency.UNKNOWN
                ));
        assertThat(classifier.classifyWithDependency(new CannotGetJdbcConnectionException(
                "database connection failed", new SQLTransientConnectionException("Hikari timeout")
        ))).isEqualTo(new ContentSearchKafkaFailureClassifier.FailureClassification(
                ContentSearchKafkaFailureClassifier.FailureType.COMMON_TRANSIENT,
                ContentSearchKafkaFailureClassifier.Dependency.MYSQL
        ));
    }

    @Test
    void keepsEs429AndConfigurationFailuresOutOfTransientAutoResumeClassification() {
        assertThat(classifier.classifyWithDependency(new RestStatusException(429, "rate limited")))
                .isEqualTo(new ContentSearchKafkaFailureClassifier.FailureClassification(
                        ContentSearchKafkaFailureClassifier.FailureType.COMMON_AMBIGUOUS,
                        ContentSearchKafkaFailureClassifier.Dependency.ELASTICSEARCH
                ));
        assertThat(classifier.classifyWithDependency(new NoSuchIndexException("missing index")).failureType())
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_CONFIGURATION);
        assertThat(classifier.classifyWithDependency(aiFailure(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}"))
                .dependency()).isEqualTo(ContentSearchKafkaFailureClassifier.Dependency.OPENAI);
    }

    @Test
    void treatsKeyAndValueDeserializationAsRecordFailuresBeforeSerializationConfigurationFailures() {
        DeserializationException keyFailure = new DeserializationException(
                "key deserialization failed", new byte[]{(byte) 0xff}, true,
                new org.apache.kafka.common.errors.SerializationException("invalid key")
        );
        DeserializationException valueFailure = new DeserializationException(
                "value deserialization failed", new byte[]{0x01}, false,
                new org.apache.kafka.common.errors.SerializationException("invalid JSON")
        );

        assertThat(classifier.classify(keyFailure))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.RECORD_PERMANENT);
        assertThat(classifier.classify(valueFailure))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.RECORD_PERMANENT);
        assertThat(classifier.shouldSkipRetry(valueFailure)).isTrue();
    }

    @Test
    void classifiesAiAvailabilityFailureAsCommonConfiguration() {
        AiUnavailableException failure = new AiUnavailableException("AI disabled");

        assertThat(classifier.classify(failure))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.COMMON_CONFIGURATION);
        assertThat(classifier.shouldSkipRetry(failure)).isTrue();
    }

    @Test
    void sendsUnclassifiedRecordFailureThroughFiniteRetryToDlt() {
        assertThat(classifier.classify(new IllegalStateException("unexpected processing failure")))
                .isEqualTo(ContentSearchKafkaFailureClassifier.FailureType.RETRY_THEN_DLT);
        assertThat(classifier.shouldSkipRetry(new IllegalStateException("unexpected processing failure")))
                .isFalse();
    }

    private AiClientException aiFailure(int status, String body) {
        HttpClientErrorException cause = HttpClientErrorException.create(
                HttpStatus.valueOf(status),
                "OpenAI failure",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
        return new AiClientException("embedding failed", status, cause);
    }

    public static class FakeElasticsearchException extends RuntimeException {

        private final FakeStatus status;

        public FakeElasticsearchException(int status, String detail) {
            super(detail);
            this.status = new FakeStatus(status);
        }

        public FakeStatus status() {
            return status;
        }
    }

    public record FakeStatus(int getStatus) {
    }

    public static class FakeElasticsearchExceptionWithoutStatus extends RuntimeException {
    }
}
