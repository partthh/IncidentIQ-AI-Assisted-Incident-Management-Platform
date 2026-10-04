package com.sentinelai.investigation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer that drains the analysis queue.
 *
 * <p>Deliberately a separate bean from {@link AnalysisJobProcessor}, which does the
 * work but owns no clock. The split exists because "stop the background worker" and
 * "there is nothing to drive" must not be the same state: with one bean, disabling the
 * worker would take the processor out of the context too, and a test that wants to step
 * the queue one job at a time would have nowhere to call.
 *
 * <p>{@code poll-interval-ms} is read here as a bare millisecond count because
 * {@code @Scheduled}'s {@code fixedDelayString} parses an ISO-8601 duration or a number
 * but not Spring's {@code 1s} shorthand, while the rest of the configuration block
 * stays {@link java.time.Duration}-typed.
 */
@Component
@ConditionalOnProperty(name = "sentinel.investigation.job-processor-enabled",
        havingValue = "true", matchIfMissing = true)
public class AnalysisQueueScheduler {

    private final AnalysisJobProcessor processor;

    public AnalysisQueueScheduler(AnalysisJobProcessor processor) {
        this.processor = processor;
    }

    /**
     * Fixed delay, not fixed rate: a batch that overruns its interval delays the next
     * tick rather than overlapping it, so a backlog cannot turn into concurrent
     * batches competing for the same provider quota.
     */
    @Scheduled(fixedDelayString = "${sentinel.investigation.poll-interval-ms:1000}")
    public void drain() {
        processor.poll();
    }
}