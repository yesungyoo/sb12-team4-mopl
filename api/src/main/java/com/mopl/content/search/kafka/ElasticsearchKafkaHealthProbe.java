package com.mopl.content.search.kafka;

import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.Dependency;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class ElasticsearchKafkaHealthProbe implements ContentSearchDependencyHealthProbe {

    private final ElasticsearchOperations elasticsearchOperations;

    public ElasticsearchKafkaHealthProbe(ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchOperations = elasticsearchOperations;
    }

    @Override
    public Dependency dependency() {
        return Dependency.ELASTICSEARCH;
    }

    @Override
    public void checkHealthy() {
        if (!elasticsearchOperations.indexOps(ContentSearchDocument.class).exists()) {
            throw new ContentSearchDependencyHealthProbe.HealthCheckFailedException(
                    "CONTENT_INDEX_MISSING",
                    "콘텐츠 Elasticsearch 인덱스를 확인할 수 없습니다."
            );
        }
    }
}
