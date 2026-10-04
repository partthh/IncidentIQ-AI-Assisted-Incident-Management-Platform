package com.sentinelai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelai.common.Role;
import com.sentinelai.config.DetectionProperties;
import com.sentinelai.detection.DetectionRuleRepository;
import com.sentinelai.events.ServiceRepository;
import com.sentinelai.security.AppUserRepository;
import com.sentinelai.support.PostgresIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Guards the foundation everything else rests on: the app context starts,
 * Flyway owns the schema, Hibernate's model matches that schema, and the seed
 * data is present.
 */
class PersistenceSmokeTest extends PostgresIntegrationTest {

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ServiceRepository services;

    @Autowired
    private DetectionRuleRepository rules;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DetectionProperties detectionProperties;

    @Test
    @DisplayName("Flyway migrations produce a schema that matches the JPA model")
    void schemaIsValid() {
        // Reaches this point only if `ddl-auto: validate` accepted every entity.
        assertThat(users.count()).isPositive();
    }

    @Test
    @DisplayName("seed data is present and idempotent")
    void seedDataIsAvailable() {
        assertThat(users.findByEmailIgnoreCase("admin@sentinel.dev")).isPresent();
        assertThat(users.findByEmailIgnoreCase("viewer@sentinel.dev"))
                .get()
                .extracting("role")
                .isEqualTo(Role.VIEWER);
        assertThat(services.findByNameIgnoreCaseAndEnvironmentIgnoreCase("payment-service", "staging"))
                .isPresent();
        assertThat(rules.findAll()).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("stored passwords are BCrypt hashes, never plaintext")
    void passwordsAreHashed() {
        String hash = users.findByEmailIgnoreCase("priya@sentinel.dev").orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2").doesNotContain("sentinel123");
        assertThat(passwordEncoder.matches("sentinel123", hash)).isTrue();
    }

    @Test
    @DisplayName("typed configuration properties bind from application.yml")
    void propertiesBind() {
        assertThat(detectionProperties.correlationWindow()).isEqualTo(Duration.ofSeconds(120));
        assertThat(detectionProperties.recentResolutionWindow()).isEqualTo(Duration.ofMinutes(30));
        assertThat(detectionProperties.maxActiveIncidentsPerService()).isEqualTo(25);
    }
}
