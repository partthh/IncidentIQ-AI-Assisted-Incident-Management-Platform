package com.sentinelai.config;

import com.sentinelai.common.EventType;
import com.sentinelai.common.HealthStatus;
import com.sentinelai.common.Role;
import com.sentinelai.common.Severity;
import com.sentinelai.detection.DetectionRuleEntity;
import com.sentinelai.detection.DetectionRuleEntity.Operator;
import com.sentinelai.detection.DetectionRuleRepository;
import com.sentinelai.events.ServiceEntity;
import com.sentinelai.events.ServiceRepository;
import com.sentinelai.security.AppUser;
import com.sentinelai.security.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the demo dataset: users, the four monitored services, and the detection
 * rules that turn raw telemetry into incidents.
 *
 * <p>Idempotent: it looks everything up before inserting, so it is safe on
 * every restart. Disabled with {@code app.seed.enabled=false}, which production
 * deployments should do.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final SeedProperties seedProperties;
    private final AppUserRepository users;
    private final ServiceRepository services;
    private final DetectionRuleRepository rules;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(SeedProperties seedProperties, AppUserRepository users, ServiceRepository services,
                      DetectionRuleRepository rules, PasswordEncoder passwordEncoder) {
        this.seedProperties = seedProperties;
        this.users = users;
        this.services = services;
        this.rules = rules;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedProperties.enabled()) {
            log.info("Seeding disabled (app.seed.enabled=false)");
            return;
        }
        seedUsers();
        seedServices();
        seedRules();
        log.info("Seed complete: {} users, {} services, {} rules",
                users.count(), services.count(), rules.count());
    }

    private void seedUsers() {
        List<AppUser> demoUsers = List.of(
                new AppUser(UUID.randomUUID(), "Dana Okafor", "admin@sentinel.dev", null, Role.ADMIN, Instant.now()),
                new AppUser(UUID.randomUUID(), "Priya Raman", "priya@sentinel.dev", null, Role.ENGINEER, Instant.now()),
                new AppUser(UUID.randomUUID(), "Marco Silva", "marco@sentinel.dev", null, Role.ENGINEER, Instant.now()),
                new AppUser(UUID.randomUUID(), "Read Only", "viewer@sentinel.dev", null, Role.VIEWER, Instant.now()));

        for (AppUser user : demoUsers) {
            if (users.findByEmailIgnoreCase(user.getEmail()).isEmpty()) {
                String hash = passwordEncoder.encode(seedProperties.password());
                users.save(new AppUser(user.getId(), user.getName(), user.getEmail(), hash, user.getRole(),
                        user.getCreatedAt()));
                log.info("Seeded user {} with role {}", user.getEmail(), user.getRole());
            }
        }
    }

    private void seedServices() {
        record Seed(String name, String ownerTeam) {
        }
        List<Seed> catalogue = List.of(
                new Seed("payment-service", "payments"),
                new Seed("booking-service", "travel"),
                new Seed("inventory-service", "supply"),
                new Seed("notification-service", "platform"));

        for (Seed seed : catalogue) {
            if (services.findByNameIgnoreCaseAndEnvironmentIgnoreCase(seed.name(), "staging").isEmpty()) {
                services.save(new ServiceEntity(UUID.randomUUID(), seed.name(), "staging",
                        HealthStatus.HEALTHY, seed.ownerTeam(), Instant.now()));
                log.info("Seeded service {}/staging", seed.name());
            }
        }
    }

    private void seedRules() {
        record RuleSeed(String code, String name, String description, EventType eventType,
                        String metricKey, Operator operator, Double threshold, Severity severity,
                        int windowSeconds, int dedupeWindow, String correlationGroup) {
        }
        List<RuleSeed> catalogue = List.of(
                new RuleSeed("PAYMENT_P95_LATENCY_HIGH",
                        "Payment p95 latency above SLO",
                        "p95 latency for the payment API exceeded its 1000 ms objective.",
                        EventType.LATENCY_SPIKE, "p95LatencyMs", Operator.GTE, 1000.0,
                        Severity.HIGH, 60, 300, "checkout-path"),
                new RuleSeed("CHECKOUT_ERROR_RATE_HIGH",
                        "Checkout error rate above 5%",
                        "More than one in twenty checkout requests failed.",
                        EventType.ERROR_RATE_SPIKE, "errorRate", Operator.GTE, 0.05,
                        Severity.HIGH, 120, 300, "checkout-path"),
                new RuleSeed("DB_POOL_SATURATED",
                        "Database connection pool near exhaustion",
                        "Connection pool utilisation reached 90%; the classic precursor to "
                                + "latency spikes and cascading timeouts.",
                        EventType.SATURATION, "poolUtilization", Operator.GTE, 0.90,
                        Severity.HIGH, 60, 180, "payments-infra"),
                new RuleSeed("CPU_SATURATED",
                        "CPU sustained above 85%",
                        "CPU saturation usually precedes queueing and latency degradation.",
                        EventType.SATURATION, "cpuUtilization", Operator.GTE, 0.85,
                        Severity.MEDIUM, 300, 600, "capacity"),
                new RuleSeed("DEPENDENCY_UNAVAILABLE",
                        "Downstream dependency unavailable",
                        "A service reported that a dependency it calls is unavailable.",
                        EventType.DEPENDENCY_FAILURE, null, Operator.GTE, null,
                        Severity.MEDIUM, 60, 300, "checkout-path"),
                new RuleSeed("HEALTH_CHECK_FAILING",
                        "Repeated failed health checks",
                        "Three consecutive failed health checks indicate a hard outage.",
                        EventType.HEALTH, "consecutiveFailures", Operator.GTE, 3.0,
                        Severity.CRITICAL, 120, 600, "checkout-path"));

        for (RuleSeed rule : catalogue) {
            if (rules.findByCode(rule.code()).isEmpty()) {
                rules.save(new DetectionRuleEntity(UUID.randomUUID(), rule.code(), rule.name(),
                        rule.description(), rule.eventType(), rule.metricKey(), rule.operator(),
                        rule.threshold(), rule.severity(), rule.windowSeconds(), rule.dedupeWindow(),
                        rule.correlationGroup(), true, Instant.now()));
                log.info("Seeded detection rule {}", rule.code());
            }
        }
    }
}
