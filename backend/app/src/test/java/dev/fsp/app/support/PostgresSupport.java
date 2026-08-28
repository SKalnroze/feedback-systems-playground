package dev.fsp.app.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres for the integration and acceptance tests.
 *
 * <p>Not an in-memory substitute: the schema uses declarative partitioning, JSONB and upserts, and
 * a test that passed against H2 would tell us nothing about whether any of that works.
 *
 * <p>The container is a singleton reused by every test class. Starting one per class would multiply
 * the suite's runtime by its number of classes for no extra confidence.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresSupport {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("fsp").withUsername("fsp").withPassword("fsp").withReuse(true);

    static {
        POSTGRES.start();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return POSTGRES;
    }
}
