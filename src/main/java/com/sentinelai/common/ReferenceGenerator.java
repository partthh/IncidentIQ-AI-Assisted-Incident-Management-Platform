package com.sentinelai.common;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Allocates the short, human-quotable identifiers engineers say out loud during
 * an incident ("INC-1042 is the one that matters").
 *
 * <p>Backed by PostgreSQL sequences rather than an in-memory counter, so ids stay
 * unique across restarts and would remain unique if the application were later
 * run as several nodes. The UUID primary key is still what relations join on; the
 * reference exists purely for humans.
 *
 * <p>Sequences are non-transactional, so a rolled-back insert leaves a gap. Gaps
 * are harmless and preferable to blocking: under contention, {@code MAX(id)+1}
 * would serialise every insert behind a table lock.
 */
@Component
public class ReferenceGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReferenceGenerator.class);
    private static final String INCIDENT_SEQUENCE = "incident_reference_seq";
    private static final String ANALYSIS_SEQUENCE = "analysis_reference_seq";

    private final JdbcTemplate jdbc;
    private final AtomicReference<String> lastIncident = new AtomicReference<>("INC-1000");
    private final AtomicReference<String> lastAnalysis = new AtomicReference<>("AI-200");

    public ReferenceGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextIncidentReference() {
        long value = nextValue(INCIDENT_SEQUENCE);
        return format("INC-", value, lastIncident);
    }

    public String nextAnalysisReference() {
        long value = nextValue(ANALYSIS_SEQUENCE);
        return format("AI-", value, lastAnalysis);
    }

    private String format(String prefix, long value, AtomicReference<String> cache) {
        String reference = prefix + value;
        cache.updateAndGet(previous -> reference.compareTo(previous) > 0 ? reference : previous);
        return reference;
    }

    private long nextValue(String sequence) {
        try {
            Long value = jdbc.queryForObject("select nextval(?)", Long.class, sequence);
            if (value == null) {
                throw new IllegalStateException("Sequence " + sequence + " returned null");
            }
            return value;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // A missing sequence means migrations did not run. Failing loudly here is
            // better than writing incidents with a null reference.
            log.error("Could not allocate from sequence {}", sequence, ex);
            throw new IllegalStateException("Reference sequence " + sequence + " is unavailable", ex);
        }
    }
}
