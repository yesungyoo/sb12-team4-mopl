package com.mopl.content.search.kafka;

import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.Dependency;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class MySqlKafkaHealthProbe implements ContentSearchDependencyHealthProbe {

    private final JdbcTemplate jdbcTemplate;

    public MySqlKafkaHealthProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Dependency dependency() {
        return Dependency.MYSQL;
    }

    @Override
    public void checkHealthy() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        if (result == null || result != 1) {
            throw new ContentSearchDependencyHealthProbe.HealthCheckFailedException(
                    "SELECT_ONE_UNEXPECTED_RESULT",
                    "MySQL health query returned an unexpected result."
            );
        }
    }
}
