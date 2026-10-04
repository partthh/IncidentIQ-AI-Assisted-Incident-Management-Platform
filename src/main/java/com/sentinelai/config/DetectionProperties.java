package com.sentinelai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sentinel.detection")
public record DetectionProperties(

        /**
         * A repeat of an active incident's fingerprint always joins that incident.
         * Once it is resolved, the next occurrence opens a <em>new</em> incident —
         * otherwise a resolved outage could never recur. This window is how long a
         * just-resolved incident is attached to its successor as recent history, so
         * an AI analysis can tell a recurrence from a novel problem.
         */
        @DefaultValue("30m") Duration recentResolutionWindow,

        /**
         * Within this window a downstream symptom from <em>another</em> service is
         * correlated into an already-open incident of the same correlation group,
         * rather than becoming its own incident.
         */
        @DefaultValue("120s") Duration correlationWindow,

        /** Minimum attached events before an incident is worth an automatic AI investigation. */
        @DefaultValue("5") int autoInvestigateAfterEvents,

        /** Guard against a runaway producer opening unbounded incidents for one service. */
        @DefaultValue("25") int maxActiveIncidentsPerService
) {
}
