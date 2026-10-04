package com.sentinelai.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the full persistence stack.
 *
 * <p>Pointing Spring at a real embedded PostgreSQL server here means every subclass
 * boots against genuine PostgreSQL. Flyway applies the real migrations and Hibernate
 * runs in {@code validate} mode, so a mismatch between an entity and
 * {@code V1__initial_schema.sql} fails the build instead of surfacing later.
 *
 * <p>Each subclass gets its own schema — see {@link TestSchema} for why a shared one
 * made this suite order-dependent. The extension is declared here rather than left to
 * subclasses so isolation cannot be forgotten by the next test class added.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(TestSchemaExtension.class)
public abstract class PostgresIntegrationTest {

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestSchema::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPostgresHolder::username);
        registry.add("spring.datasource.password", EmbeddedPostgresHolder::password);
        // Flyway creates and migrates this class's schema. Without the schema names
        // Flyway would migrate "public" while Hibernate validated against the test
        // schema, and the two would disagree about which tables exist.
        registry.add("spring.flyway.schemas", TestSchema::name);
        registry.add("spring.flyway.default-schema", TestSchema::name);
        registry.add("spring.flyway.create-schemas", () -> "true");
        // Unqualified table names in entities and JPQL resolve against this schema.
        registry.add("spring.jpa.properties.hibernate.default_schema", TestSchema::name);

        registry.add("sentinel.investigation.provider", () -> "stub");
        registry.add("sentinel.investigation.poll-interval-ms", () -> "200");
        registry.add("sentinel.investigation.stub-latency-ms", () -> "0");
        registry.add("sentinel.seed.enabled", () -> "true");
    }
}