package com.mopl.content.search.kafka;

import com.mopl.infrastructure.ai.exception.AiClientException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.TimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.elasticsearch.NoSuchIndexException;
import org.springframework.data.elasticsearch.ResourceNotFoundException;
import org.springframework.data.elasticsearch.RestStatusException;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Classifies only failures for which the cause chain provides useful evidence. */
public class ContentSearchKafkaFailureClassifier {

    public FailureType classify(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        boolean ambiguousElasticsearchFailure = false;
        boolean dataAccessResourceFailure = false;
        while (current != null && visited.add(current)) {
            if (current instanceof DeserializationException) {
                return FailureType.RECORD_PERMANENT;
            }
            if (current instanceof AiUnavailableException) {
                return FailureType.COMMON_CONFIGURATION;
            }
            if (current instanceof NoSuchIndexException || current instanceof ResourceNotFoundException) {
                return FailureType.COMMON_CONFIGURATION;
            }
            if (current instanceof org.springframework.data.elasticsearch.ResourceFailureException) {
                return FailureType.COMMON_TRANSIENT;
            }
            if (current instanceof DataAccessResourceFailureException) {
                dataAccessResourceFailure = true;
                current = current.getCause();
                continue;
            }
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
                    || isDatabaseConnectionFailure(current)
                    || isNetworkFailure(current)) {
                return FailureType.COMMON_TRANSIENT;
            }
            if (isCommonKafkaConfigurationFailure(current)) {
                return FailureType.COMMON_CONFIGURATION;
            }

            FailureType elasticsearchType = classifyElasticsearchFailure(current);
            if (elasticsearchType != FailureType.RETRY_THEN_DLT) {
                if (elasticsearchType == FailureType.COMMON_AMBIGUOUS
                        && current instanceof DataAccessException) {
                    ambiguousElasticsearchFailure = true;
                    current = current.getCause();
                    continue;
                }
                return elasticsearchType;
            }
            current = current.getCause();
        }
        if (dataAccessResourceFailure) {
            return FailureType.COMMON_TRANSIENT;
        }
        return ambiguousElasticsearchFailure ? FailureType.COMMON_AMBIGUOUS : FailureType.RETRY_THEN_DLT;
    }

    /**
     * Returns the existing PR2 classification together with a dependency only when
     * the cause chain identifies it explicitly enough for an automatic health probe.
     */
    public FailureClassification classifyWithDependency(Throwable failure) {
        return new FailureClassification(classify(failure), dependencyOf(failure));
    }

    private Dependency dependencyOf(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        boolean springDataResourceFailure = false;
        while (current != null && visited.add(current)) {
            if (current instanceof AiClientException) {
                return Dependency.OPENAI;
            }
            if (current instanceof DataAccessResourceFailureException) {
                springDataResourceFailure = true;
            }
            if (current instanceof NoSuchIndexException
                    || current instanceof ResourceNotFoundException
                    || current instanceof org.springframework.data.elasticsearch.ResourceFailureException
                    || current instanceof RestStatusException
                    || current instanceof UncategorizedElasticsearchException
                    || current instanceof ResourceAccessException
                    || current.getClass().getName().contains("Elasticsearch")) {
                return Dependency.ELASTICSEARCH;
            }
            if (current instanceof CannotGetJdbcConnectionException
                    || current instanceof SQLTransientConnectionException
                    || current instanceof SQLRecoverableException
                    || current instanceof SQLException sqlException
                    && sqlException.getSQLState() != null
                    && sqlException.getSQLState().startsWith("08")) {
                return Dependency.MYSQL;
            }
            if (springDataResourceFailure
                    && current.getClass().getName().equals("org.apache.http.ConnectionClosedException")) {
                // Spring Data Elasticsearch translates the Apache HTTP client's closed-connection
                // cause to DataAccessResourceFailureException. Require that outer translation;
                // a standalone ConnectionClosedException does not identify its dependency.
                return Dependency.ELASTICSEARCH;
            }
            if (springDataResourceFailure && (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.SocketException
                    || isNetworkFailure(current))) {
                return Dependency.ELASTICSEARCH;
            }
            current = current.getCause();
        }
        return Dependency.UNKNOWN;
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
            if (!openAi) {
                return FailureType.COMMON_AMBIGUOUS;
            }
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

    private boolean isDatabaseConnectionFailure(Throwable failure) {
        if (failure instanceof CannotGetJdbcConnectionException
                || failure instanceof SQLTransientConnectionException
                || failure instanceof SQLRecoverableException) {
            return true;
        }
        if (failure instanceof SQLException sqlException) {
            String sqlState = sqlException.getSQLState();
            return sqlState != null && sqlState.startsWith("08");
        }
        return false;
    }

    private FailureType classifyElasticsearchFailure(Throwable failure) {
        Integer status = null;
        String detail = null;
        if (failure instanceof RestStatusException restStatusException) {
            status = restStatusException.getStatus();
            detail = restStatusException.getMessage();
        } else if (failure instanceof UncategorizedElasticsearchException elasticsearchException) {
            status = elasticsearchException.getStatusCode();
            detail = firstNonBlank(elasticsearchException.getResponseBody(), elasticsearchException.getMessage());
        } else if (failure instanceof org.springframework.data.elasticsearch.ResourceFailureException) {
            return FailureType.COMMON_TRANSIENT;
        } else if (!failure.getClass().getName().contains("Elasticsearch")) {
            return FailureType.RETRY_THEN_DLT;
        } else {
            status = reflectedStatus(failure);
            detail = reflectedResponseBody(failure);
        }

        if (status == null) {
            return FailureType.COMMON_AMBIGUOUS;
        }
        return classifyHttpFailure(status, detail, false);
    }

    private Integer reflectedStatus(Throwable failure) {
        try {
            for (String methodName : new String[]{"getStatusCode", "getStatus", "status"}) {
                try {
                    Object status = failure.getClass().getMethod(methodName).invoke(failure);
                    if (status instanceof Number number) {
                        return number.intValue();
                    }
                    if (status != null) {
                        Object code = status.getClass().getMethod("getStatus").invoke(status);
                        if (code instanceof Number number) {
                            return number.intValue();
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try the next status accessor used by Spring Data or the Elasticsearch client.
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Continue through the cause chain when an Elasticsearch client exposes another exception shape.
        }
        return null;
    }

    private String reflectedResponseBody(Throwable failure) {
        try {
            Object body = failure.getClass().getMethod("getResponseBody").invoke(failure);
            return body instanceof String value ? value : failure.getMessage();
        } catch (ReflectiveOperationException ignored) {
            return failure.getMessage();
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
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

    public enum Dependency {
        ELASTICSEARCH,
        MYSQL,
        OPENAI,
        UNKNOWN
    }

    public record FailureClassification(FailureType failureType, Dependency dependency) {
    }
}
