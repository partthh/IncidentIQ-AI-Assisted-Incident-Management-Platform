package com.sentinelai.detection;

import com.sentinelai.common.EventType;
import com.sentinelai.common.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A data-driven detection rule. Keeping rules in the database (rather than in
 * code) means an operator can tune detection without a redeploy, and the rule
 * that opened an incident is always recorded as data.
 */
@Entity
@Table(name = "detection_rules")
public class DetectionRuleEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 80)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    /** Metadata key compared against {@link #threshold}; null matches any event of the type. */
    @Column(name = "metric_key", length = 80)
    private String metricKey;

    /** Comparison operator applied between a metric value and {@link #threshold}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "comparator", nullable = false, length = 6)
    private Operator operator;

    @Column
    private Double threshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    @Column(name = "window_seconds", nullable = false)
    private Integer windowSeconds;

    @Column(name = "dedupe_window_seconds", nullable = false)
    private Integer dedupeWindowSeconds;

    @Column(name = "correlation_group", length = 80)
    private String correlationGroup;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DetectionRuleEntity() {
        // for JPA
    }

    public DetectionRuleEntity(UUID id, String code, String name, String description, EventType eventType,
                              String metricKey, Operator operator, Double threshold, Severity severity,
                              Integer windowSeconds, Integer dedupeWindowSeconds, String correlationGroup,
                              boolean enabled, Instant createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.eventType = eventType;
        this.metricKey = metricKey;
        this.operator = operator;
        this.threshold = threshold;
        this.severity = severity;
        this.windowSeconds = windowSeconds;
        this.dedupeWindowSeconds = dedupeWindowSeconds;
        this.correlationGroup = correlationGroup;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public Operator getOperator() {
        return operator;
    }

    public Double getThreshold() {
        return threshold;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Integer getWindowSeconds() {
        return windowSeconds;
    }

    public Integer getDedupeWindowSeconds() {
        return dedupeWindowSeconds;
    }

    public String getCorrelationGroup() {
        return correlationGroup;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** How a measurement is compared against a rule threshold. */
    public enum Operator {
        GT,
        GTE,
        LT,
        LTE,
        EQ,
        NEQ;

        public boolean test(double value, double threshold) {
            return switch (this) {
                case GT -> value > threshold;
                case GTE -> value >= threshold;
                case LT -> value < threshold;
                case LTE -> value <= threshold;
                case EQ -> Math.abs(value - threshold) < 1e-9;
                case NEQ -> Math.abs(value - threshold) >= 1e-9;
            };
        }
    }
}
