package com.sentinelai.detection;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelai.common.EventType;
import com.sentinelai.common.HealthStatus;
import com.sentinelai.common.Severity;
import com.sentinelai.detection.DetectionRuleEntity.Operator;
import com.sentinelai.events.EventEntity;
import com.sentinelai.events.ServiceEntity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rule engine decides who gets paged, so the tests here are about the cases
 * where being wrong is expensive:
 *
 * <ul>
 *   <li>a <em>false positive</em> manufactures an incident nobody had</li>
 *   <li>a <em>false negative</em> loses a real outage</li>
 *   <li>a missing metric must never be treated as a passing one</li>
 * </ul>
 */
class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private final ServiceEntity service = new ServiceEntity(UUID.randomUUID(), "payment", "staging",
            HealthStatus.HEALTHY, "payments", Instant.now());

    @Nested
    @DisplayName("threshold matching")
    class Thresholds {

        @Test
        void firesWhenMetricIsAboveThreshold() {
            DetectionRuleEntity rule = rule("P95_HIGH", EventType.METRIC, "p95LatencyMs",
                    Operator.GTE, 1000.0, Severity.HIGH);

            List<RuleMatch> matches = engine.evaluate(List.of(rule), event(EventType.METRIC,
                    Map.of("p95LatencyMs", 1500.0)));

            assertThat(matches).hasSize(1);
            assertThat(matches.get(0).ruleCode()).isEqualTo("P95_HIGH");
            assertThat(matches.get(0).observedValue()).isEqualTo(1500.0);
            assertThat(matches.get(0).severity()).isEqualTo(Severity.HIGH);
        }

        @Test
        void respectsExclusiveComparators() {
            // EQ uses a tolerance, so 0.05 must not match a 0.05 threshold by rounding.
            assertThat(engine.evaluate(
                    List.of(rule("ERR", EventType.METRIC, "errorRate", Operator.GT, 0.05, Severity.HIGH)),
                    event(EventType.METRIC, Map.of("errorRate", 0.05)))).isEmpty();

            assertThat(engine.evaluate(
                    List.of(rule("ERR", EventType.METRIC, "errorRate", Operator.LT, 0.05, Severity.HIGH)),
                    event(EventType.METRIC, Map.of("errorRate", 0.0500001)))).isEmpty();
        }

        @Test
        void boundaryIsInclusiveForGte() {
            assertThat(engine.evaluate(
                    List.of(rule("UTIL", EventType.METRIC, "poolUtilization", Operator.GTE, 0.90, Severity.HIGH)),
                    event(EventType.METRIC, Map.of("poolUtilization", 0.90)))).hasSize(1);
        }

        @Test
        void numericStringsAreAcceptedBecauseProducersStringifyNumbers() {
            // jsonb metadata crosses many producers; "1500" arrives in practice. Refusing
            // it would silently drop real alerts, so the value is parsed — but only when
            // it parses cleanly to a number.
            DetectionRuleEntity rule = rule("P95", EventType.METRIC, "p95LatencyMs",
                    Operator.GTE, 1000.0, Severity.HIGH);

            assertThat(engine.evaluate(List.of(rule),
                    event(EventType.METRIC, Map.of("p95LatencyMs", "1500")))).hasSize(1);

            assertThat(engine.evaluate(List.of(rule),
                    event(EventType.METRIC, Map.of("p95LatencyMs", "1500ms")))).isEmpty();

            assertThat(engine.evaluate(List.of(rule),
                    event(EventType.METRIC, Map.of("p95LatencyMs", "unknown")))).isEmpty();
        }
    }

    @Nested
    @DisplayName("fail-safe behaviour")
    class FailSafe {

        @Test
        void missingMetricDoesNotFire() {
            DetectionRuleEntity rule = rule("P95", EventType.METRIC, "p95LatencyMs",
                    Operator.GTE, 1000.0, Severity.HIGH);

            assertThat(engine.evaluate(List.of(rule), event(EventType.METRIC, Map.of("other", 5000.0))))
                    .isEmpty();
        }

        @Test
        void nullMetricValueDoesNotFire() {
            DetectionRuleEntity rule = rule("P95", EventType.METRIC, "p95LatencyMs",
                    Operator.GTE, 1000.0, Severity.HIGH);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("p95LatencyMs", null);

            assertThat(engine.evaluate(List.of(rule), event(EventType.METRIC, metadata))).isEmpty();
        }

        @Test
        void ruleComparingAMetricWithoutAThresholdIsIgnored() {
            DetectionRuleEntity rule = rule("BROKEN", EventType.METRIC, "p95LatencyMs",
                    Operator.GTE, null, Severity.HIGH);

            assertThat(engine.evaluate(List.of(rule),
                    event(EventType.METRIC, Map.of("p95LatencyMs", 9999.0)))).isEmpty();
        }

        @Test
        void rulesForOtherEventTypesAreSkipped() {
            DetectionRuleEntity metric = rule("P95", EventType.METRIC, "p95LatencyMs",
                    Operator.GTE, 1.0, Severity.HIGH);
            DetectionRuleEntity error = rule("ERR", EventType.LOG, "errorRate",
                    Operator.GTE, 0.0, Severity.LOW);

            List<RuleMatch> matches = engine.evaluate(List.of(metric, error),
                    event(EventType.LOG, Map.of("errorRate", 0.9)));

            assertThat(matches).extracting(RuleMatch::ruleCode).containsExactly("ERR");
        }

        @Test
        void noRulesProducesNoMatches() {
            assertThat(engine.evaluate(List.of(), event(EventType.LOG, Map.of()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("type-only rules")
    class TypeOnly {

        @Test
        void firesForEveryEventOfTheType() {
            DetectionRuleEntity rule = new DetectionRuleEntity(UUID.randomUUID(), "DEP_DOWN",
                    "Dependency unavailable", "A dependency reported itself unavailable.",
                    EventType.DEPENDENCY_FAILURE, null, Operator.EQ, null, Severity.CRITICAL,
                    300, 600, "checkout-path", true, Instant.now());

            assertThat(engine.evaluate(List.of(rule),
                    event(EventType.DEPENDENCY_FAILURE, Map.of("dependency", "stripe")))).hasSize(1);
        }

        @Test
        void stillRequiresTheEventTypeToMatch() {
            DetectionRuleEntity rule = new DetectionRuleEntity(UUID.randomUUID(), "DEP_DOWN",
                    "Dependency unavailable", null, EventType.DEPENDENCY_FAILURE,
                    null, Operator.EQ, null, Severity.CRITICAL, 300, 600, null, true, Instant.now());

            assertThat(engine.evaluate(List.of(rule), event(EventType.METRIC, Map.of("p95LatencyMs", 1.0))))
                    .isEmpty();
        }
    }

    @Test
    void matchCarriesTheValuesNeededToExplainWhyItFired() {
        DetectionRuleEntity rule = new DetectionRuleEntity(UUID.randomUUID(), "POOL", "DB pool saturated",
                null, EventType.METRIC, "poolUtilization", Operator.GTE, 0.90, Severity.CRITICAL,
                120, 900, "payments-infra", true, Instant.now());

        RuleMatch match = engine.evaluate(List.of(rule),
                event(EventType.METRIC, Map.of("poolUtilization", 0.97))).get(0);

        assertThat(match.observedValue()).isEqualTo(0.97);
        assertThat(match.threshold()).isEqualTo(0.90);
        assertThat(match.observedKey()).isEqualTo("poolUtilization");
        assertThat(match.correlationGroup()).isEqualTo("payments-infra");
        assertThat(match.dedupeWindowSeconds()).isEqualTo(900);
        assertThat(match.windowSeconds()).isEqualTo(120);
        // The evidence string is what an engineer reads on the timeline: it has to
        // stand alone, months later, without the rule table in front of them.
        assertThat(match.evidence()).contains("poolUtilization=0.97").contains("GTE 0.9").contains("120s");
    }

    private DetectionRuleEntity rule(String code, EventType type, String metric, Operator operator,
                                     Double threshold, Severity severity) {
        return new DetectionRuleEntity(UUID.randomUUID(), code, code, null, type, metric, operator,
                threshold, severity, 60, 300, null, true, Instant.now());
    }

    private EventEntity event(EventType type, Map<String, Object> metadata) {
        Instant now = Instant.parse("2026-10-04T10:00:00Z");
        return new EventEntity(UUID.randomUUID(), "simulator", "evt-" + UUID.randomUUID(), service,
                type, Severity.INFO, "test event", "NullPointerException", now, now, metadata);
    }
}