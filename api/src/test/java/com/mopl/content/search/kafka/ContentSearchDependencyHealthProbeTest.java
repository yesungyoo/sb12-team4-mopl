package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.search.document.ContentSearchDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.jdbc.core.JdbcTemplate;

class ContentSearchDependencyHealthProbeTest {

    @Test
    void elasticsearchProbeUsesReadOnlyContentIndexExistenceCheck() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(operations.indexOps(ContentSearchDocument.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);

        new ElasticsearchKafkaHealthProbe(operations).checkHealthy();

        verify(indexOperations).exists();
    }

    @Test
    void elasticsearchProbeFailsClosedWhenContentIndexIsMissing() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(operations.indexOps(ContentSearchDocument.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(false);

        assertThatThrownBy(() -> new ElasticsearchKafkaHealthProbe(operations).checkHealthy())
                .isInstanceOf(ContentSearchDependencyHealthProbe.HealthCheckFailedException.class);
    }

    @Test
    void mysqlProbeExecutesReadOnlySelectOneAgainstApplicationJdbcTemplate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        new MySqlKafkaHealthProbe(jdbcTemplate).checkHealthy();

        verify(jdbcTemplate).queryForObject("SELECT 1", Integer.class);
    }
}
