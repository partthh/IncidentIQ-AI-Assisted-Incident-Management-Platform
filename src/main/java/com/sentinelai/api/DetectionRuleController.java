package com.sentinelai.api;

import com.sentinelai.common.EventType;
import com.sentinelai.common.NotFoundException;
import com.sentinelai.detection.DetectionRuleEntity;
import com.sentinelai.detection.DetectionRuleEntity.Operator;
import com.sentinelai.detection.DetectionRuleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Detection rules, read-only over HTTP.
 *
 * <p>Read-only is a product decision, not an omission. The rules decide whether a
 * support engineer gets woken up, so changing one is a change to that person's night
 * — but it is also a change to detection behaviour under live load, which cannot be
 * reasoned about safely from an HTTP request. Rules are seeded by migration here and
 * edited through a reviewed change, where a diff exists.
 */
@RestController
@RequestMapping("/api/v1/detection-rules")
public class DetectionRuleController {

    private final DetectionRuleRepository rules;

    public DetectionRuleController(DetectionRuleRepository rules) {
        this.rules = rules;
    }

    /** All rules, enabled or not: a disabled rule is still part of the story. */
    @GetMapping
    public List<RuleView> list(@RequestParam(required = false) Boolean enabled,
                               @RequestParam(required = false) EventType eventType) {
        return rules.findAll().stream()
                .filter(rule -> enabled == null || rule.isEnabled() == enabled)
                .filter(rule -> eventType == null || rule.getEventType() == eventType)
                .map(DetectionRuleController::toView)
                .toList();
    }

    /** Only what the rule engine consults. Useful for verifying a deployment took effect. */
    @GetMapping("/active")
    public List<RuleView> active() {
        return rules.findByEnabledTrueOrderByCodeAsc().stream()
                .map(DetectionRuleController::toView)
                .toList();
    }

    @GetMapping("/{code}")
    public RuleView get(@PathVariable String code) {
        String normalised = code.trim().toUpperCase(Locale.ROOT);
        return rules.findByCode(normalised)
                .map(DetectionRuleController::toView)
                .orElseThrow(() -> NotFoundException.of("Detection rule", normalised));
    }

    /**
     * A rule as data.
     *
     * <p>{@code condition} is rendered as text rather than nested JSON because the
     * only consumer is a human reading "why did this fire", and a flat sentence is
     * readable where a five-node predicate object is not. The structured fields are
     * still returned separately so a UI can build an editor from them later.
     */
    private static RuleView toView(DetectionRuleEntity rule) {
        return new RuleView(
                rule.getId(),
                rule.getCode(),
                rule.getName(),
                rule.getDescription(),
                rule.getEventType(),
                rule.getMetricKey(),
                rule.getOperator(),
                rule.getThreshold(),
                rule.getSeverity(),
                rule.getWindowSeconds(),
                rule.getDedupeWindowSeconds(),
                rule.getCorrelationGroup(),
                rule.isEnabled(),
                describe(rule),
                rule.getCreatedAt());
    }

    private static String describe(DetectionRuleEntity rule) {
        if (rule.getMetricKey() == null) {
            return rule.getEventType() + " event, any value";
        }
        return rule.getEventType() + "." + rule.getMetricKey() + " "
                + symbol(rule.getOperator()) + " " + trim(rule.getThreshold())
                + " within " + rule.getWindowSeconds() + "s";
    }

    private static String symbol(Operator operator) {
        return switch (operator) {
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case EQ -> "=";
            case NEQ -> "!=";
        };
    }

    /** Latency thresholds are integers; ratios would print a row of meaningless decimals. */
    private static String trim(Double value) {
        if (value == null) {
            return "n/a";
        }
        return value == Math.rint(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    public record RuleView(UUID id, String code, String name, String description, EventType eventType,
                           String metricKey, Operator operator, Double threshold,
                           com.sentinelai.common.Severity severity, Integer windowSeconds,
                           Integer dedupeWindowSeconds, String correlationGroup, boolean enabled,
                           String condition, Instant createdAt) {
    }
}