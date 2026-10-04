package com.sentinelai.detection;

import com.sentinelai.events.EventEntity;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Evaluates detection rules against a single event.
 *
 * <p>Pure and synchronous by design: detection must never depend on an external
 * service being reachable, so it completes in microseconds and cannot be slowed
 * down by the AI path. Rules live in the database, so this class takes them as
 * input rather than loading them itself — that keeps it trivially unit-testable.
 *
 * <p>Matching is deliberately fail-safe. A rule whose metric is missing from an
 * event's metadata is <em>not</em> matched: guessing would manufacture incidents,
 * and skipping one event never loses data, since the event itself is still stored
 * and can be re-evaluated by hand.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    /**
     * @param rules enabled rules, already narrowed to the event's type where possible
     * @return every rule whose threshold the event satisfies; empty when none do
     */
    public List<RuleMatch> evaluate(List<DetectionRuleEntity> rules, EventEntity event) {
        if (rules.isEmpty()) {
            return List.of();
        }
        return rules.stream()
                .filter(rule -> matchesType(rule, event))
                .map(rule -> evaluateOne(rule, event))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private boolean matchesType(DetectionRuleEntity rule, EventEntity event) {
        return rule.getEventType() == event.getEventType();
    }

    private RuleMatch evaluateOne(DetectionRuleEntity rule, EventEntity event) {
        // Type-only rule: every event of this type trips it (e.g. a dependency
        // reporting itself unavailable carries no threshold).
        if (rule.getMetricKey() == null || rule.getMetricKey().isBlank()) {
            if (rule.getThreshold() != null) {
                log.warn("Detection rule {} has no metric_key but a threshold of {}; treating as type-only",
                        rule.getCode(), rule.getThreshold());
            }
            return toMatch(rule, null, "event type " + event.getEventType() + " reported");
        }

        Double observed = event.numericMetadata(rule.getMetricKey());
        if (observed == null) {
            return null;
        }
        Double threshold = rule.getThreshold();
        if (threshold == null) {
            log.warn("Detection rule {} compares metric {} but declares no threshold; ignoring",
                    rule.getCode(), rule.getMetricKey());
            return null;
        }
        if (!rule.getOperator().test(observed, threshold)) {
            return null;
        }
        return toMatch(rule, observed, describeEvidence(rule, observed, threshold));
    }

    private String describeEvidence(DetectionRuleEntity rule, double observed, double threshold) {
        return "observed " + rule.getMetricKey() + "=" + trim(observed)
                + " against " + rule.getOperator() + " " + trim(threshold)
                + " over a " + rule.getWindowSeconds() + "s window";
    }

    private String trim(double value) {
        return value % 1 == 0 ? String.valueOf((long) value) : String.valueOf(value);
    }

    private RuleMatch toMatch(DetectionRuleEntity rule, Double observed, String evidence) {
        return new RuleMatch(
                rule.getCode(),
                rule.getName(),
                rule.getSeverity(),
                rule.getCorrelationGroup(),
                rule.getDedupeWindowSeconds() == null ? 300 : rule.getDedupeWindowSeconds(),
                rule.getWindowSeconds() == null ? 60 : rule.getWindowSeconds(),
                observed,
                rule.getThreshold(),
                rule.getMetricKey(),
                evidence);
    }
}
