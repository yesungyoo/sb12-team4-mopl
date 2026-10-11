package com.mopl.reindexcli;

import java.util.Map;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
public class ReindexCliPersistenceSafetyConfiguration {

    private static final String HIBERNATE_DDL_AUTO = "hibernate.hbm2ddl.auto";

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    HibernatePropertiesCustomizer schemaValidationOnly() {
        return hibernateProperties ->
                hibernateProperties.put(HIBERNATE_DDL_AUTO, "validate");
    }
}
