package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.infrastructure.ai.exception.AiClientException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
