package com.mopl.content.search.kafka;

import com.mopl.infrastructure.ai.exception.AiClientException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Classifies only failures for which the cause chain provides useful evidence. */
public class ContentSearchKafkaFailureClassifier {

    public FailureType classify(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof AiClientException aiFailure) {
                FailureType type = classifyAiFailure(aiFailure);
                if (type != FailureType.RETRY_THEN_DLT) {
                    return type;
                }
            }

            if (current instanceof RestClientResponseException responseFailure) {
                FailureType type = classifyHttpFailure(
                        responseFailure.getStatusCode().value(),
                        responseFailure.getResponseBodyAsString(),
                        false
                );
                if (type != FailureType.RETRY_THEN_DLT) {
                    return type;
                }
            }

            if (current instanceof ResourceAccessException
                    || current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof org.apache.kafka.common.errors.TimeoutException
                    || current instanceof InterruptedException
                    || isNetworkFailure(current)) {
                return FailureType.COMMON_TRANSIENT;
            }
            if (isCommonKafkaConfigurationFailure(current)) {
                return FailureType.COMMON_CONFIGURATION;
            }

            FailureType elasticsearchType = classifyElasticsearchFailure(current);
            if (elasticsearchType != FailureType.RETRY_THEN_DLT) {
                return elasticsearchType;
            }
            current = current.getCause();
        }
        return FailureType.RETRY_THEN_DLT;
    }

    public boolean shouldSkipRetry(Throwable failure) {
        FailureType type = classify(failure);
        return type == FailureType.RECORD_PERMANENT
                || type == FailureType.COMMON_CONFIGURATION
                || type == FailureType.COMMON_AMBIGUOUS;
    }

    private FailureType classifyAiFailure(AiClientException exception) {
        Integer status = exception.httpStatusCode();
        if (status == null) {
            return FailureType.RETRY_THEN_DLT;
        }
        String body = findResponseBody(exception);
        return classifyHttpFailure(status, body, true);
    }

    private FailureType classifyHttpFailure(int status, String body, boolean openAi) {
        String detail = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 401 || status == 403) {
            return FailureType.COMMON_CONFIGURATION;
        }
        if (status == 429) {
            if (containsAny(detail, "rate_limit_exceeded", "rate_limit_reached")) {
                return FailureType.COMMON_TRANSIENT;
            }
            if (containsAny(detail, "insufficient_quota", "billing_hard_limit", "quota_exceeded")) {
                return FailureType.COMMON_CONFIGURATION;
            }
            return FailureType.COMMON_AMBIGUOUS;
        }
        if (status == 400) {
            boolean recordParsingType = containsAny(
                    detail, "mapper_parsing_exception", "document_parsing_exception", "x_content_parse_exception"
            );
            boolean recordParsingDetail = containsAny(
                    detail, "failed to parse field", "failed to parse value", "failed to parse document"
            );
            if (!openAi && recordParsingType && recordParsingDetail) {
                return FailureType.RECORD_PERMANENT;
            }
            return FailureType.COMMON_AMBIGUOUS;
        }
        if (status == 404) {
            return FailureType.COMMON_CONFIGURATION;
        }
        if (status == 408 || status == 425 || status == 500
                || status == 502 || status == 503 || status == 504) {
            return FailureType.COMMON_TRANSIENT;
        }
        return FailureType.RETRY_THEN_DLT;
    }

    private FailureType classifyElasticsearchFailure(Throwable failure) {
        String className = failure.getClass().getName();
        if (!className.contains("Elasticsearch")
                && !className.contains("RestStatusException")) {
            return FailureType.RETRY_THEN_DLT;
        }

        Integer status = reflectedStatus(failure);
        if (status == null) {
            return FailureType.COMMON_AMBIGUOUS;
        }
        return classifyHttpFailure(status, failure.getMessage(), false);
    }

    private Integer reflectedStatus(Throwable failure) {
        try {
            Object status = failure.getClass().getMethod("status").invoke(failure);
            if (status instanceof Number number) {
                return number.intValue();
            }
            if (status != null) {
                Object code = status.getClass().getMethod("getStatus").invoke(status);
                if (code instanceof Number number) {
                    return number.intValue();
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Unknown Elasticsearch exception shapes are retried and then isolated in the DLT.
        }
        return null;
    }

    private String findResponseBody(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof RestClientResponseException responseFailure) {
                return responseFailure.getResponseBodyAsString();
            }
            current = current.getCause();
        }
        return failure.getMessage();
    }

    private boolean isNetworkFailure(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        return name.equals("UnknownHostException")
                || name.equals("ConnectTimeoutException")
                || name.equals("NoHttpResponseException")
                || name.equals("SocketException")
                || name.equals("NotEnoughReplicasException")
                || name.equals("NotEnoughReplicasAfterAppendException")
                || name.equals("LeaderNotAvailableException")
                || name.equals("NotLeaderOrFollowerException")
                || name.equals("BrokerNotAvailableException")
                || name.equals("NetworkException");
    }

    private boolean isCommonKafkaConfigurationFailure(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        return name.equals("AuthenticationException")
                || name.equals("AuthorizationException")
                || name.equals("TopicAuthorizationException")
                || name.equals("ClusterAuthorizationException")
                || name.equals("GroupAuthorizationException")
                || name.equals("SerializationException")
                || name.equals("InvalidTopicException");
    }

    private boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    public enum FailureType {
        RECORD_PERMANENT,
        RETRY_THEN_DLT,
        COMMON_TRANSIENT,
        COMMON_CONFIGURATION,
        COMMON_AMBIGUOUS
    }
}
