package com.sentinelai.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the full persistence stack.
 *
 * <p>Pointing Spring at the shared embedded server here means every subclass
 * boots against genuine PostgreSQL. Flyway applies the real migrations and
 * Hibernate runs in {@code validate} mode, so a mismatch between an entity and
 * {@code V1__initial_schema.sql} fails the build instead of surfacing later.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresHolder::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPostgresHolder::username);
        registry.add("spring.datasource.password", EmbeddedPostgresHolder::password);
        registry.add("sentinel.investigation.provider", () -> "stub");
        registry.add("sentinel.investigation.poll-interval-ms", () -> "200");
        registry.add("sentinel.investigation.stub-latency-ms", () -> "0");
        registry.add("sentinel.seed.enabled", () -> "true");
    }
}
