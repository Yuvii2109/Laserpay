package com.laserpay.pdei.orchestrator.model;

import java.time.Duration;

/**
 * Every duration the workflow sleeps on, carried INSIDE the workflow input.
 *
 * <p>Workflow code may not read Spring configuration: a property change between the original
 * execution and a replay would make the two diverge and Temporal would reject the replay. So the
 * listener resolves the configured values once, at start time, and pins them into
 * {@link DisputeCaseInput}. A running case therefore keeps the timings it was started with, which
 * is also the behaviour an operator expects when they retune the service.</p>
 *
 * @param missingEvidenceWait          step 4 budget. Contract section 10: max 7 days.
 * @param evidenceWaitSlice            how often the step 4 wait wakes up to re-evaluate and to
 *                                     consider continue-as-new. Purely an implementation detail of
 *                                     the wait; it does not extend {@code missingEvidenceWait}.
 * @param humanApprovalTimeout         step 8 first wait; on expiry the case escalates.
 * @param escalationTimeout            step 8 second wait, after escalation. On expiry the case
 *                                     closes as {@link CaseResolution#ESCALATION_EXPIRED}.
 * @param followUpInterval             step 11 tick interval.
 * @param followUpMaxDuration          step 11 hard ceiling, independent of the dispute deadline.
 * @param continueAsNewHistoryThreshold event-history length at which the workflow continues as new
 *                                     from inside a long wait.
 * @param maxAssessmentRounds          how many times steps 2-8 may repeat before the case is closed
 *                                     as {@link CaseResolution#EVIDENCE_INSUFFICIENT}.
 */
public record CaseTimers(
        Duration missingEvidenceWait,
        Duration evidenceWaitSlice,
        Duration humanApprovalTimeout,
        Duration escalationTimeout,
        Duration followUpInterval,
        Duration followUpMaxDuration,
        int continueAsNewHistoryThreshold,
        int maxAssessmentRounds) {

    /** Contract section 10: "max 7 days". */
    public static final Duration DEFAULT_MISSING_EVIDENCE_WAIT = Duration.ofDays(7);
    public static final Duration DEFAULT_EVIDENCE_WAIT_SLICE = Duration.ofHours(12);
    public static final Duration DEFAULT_HUMAN_APPROVAL_TIMEOUT = Duration.ofHours(48);
    public static final Duration DEFAULT_ESCALATION_TIMEOUT = Duration.ofHours(72);
    public static final Duration DEFAULT_FOLLOW_UP_INTERVAL = Duration.ofHours(24);
    public static final Duration DEFAULT_FOLLOW_UP_MAX_DURATION = Duration.ofDays(45);
    public static final int DEFAULT_CONTINUE_AS_NEW_HISTORY_THRESHOLD = 8_000;
    public static final int DEFAULT_MAX_ASSESSMENT_ROUNDS = 3;

    public static CaseTimers defaults() {
        return new CaseTimers(
                DEFAULT_MISSING_EVIDENCE_WAIT,
                DEFAULT_EVIDENCE_WAIT_SLICE,
                DEFAULT_HUMAN_APPROVAL_TIMEOUT,
                DEFAULT_ESCALATION_TIMEOUT,
                DEFAULT_FOLLOW_UP_INTERVAL,
                DEFAULT_FOLLOW_UP_MAX_DURATION,
                DEFAULT_CONTINUE_AS_NEW_HISTORY_THRESHOLD,
                DEFAULT_MAX_ASSESSMENT_ROUNDS);
    }

    /**
     * Replace anything null or non-positive with its default. A workflow started by an older
     * producer, or from a hand-written ops request, still gets a complete, sane set of timers.
     */
    public static CaseTimers orDefaults(CaseTimers timers) {
        CaseTimers d = defaults();
        if (timers == null) {
            return d;
        }
        return new CaseTimers(
                positiveOr(timers.missingEvidenceWait, d.missingEvidenceWait),
                positiveOr(timers.evidenceWaitSlice, d.evidenceWaitSlice),
                positiveOr(timers.humanApprovalTimeout, d.humanApprovalTimeout),
                positiveOr(timers.escalationTimeout, d.escalationTimeout),
                positiveOr(timers.followUpInterval, d.followUpInterval),
                positiveOr(timers.followUpMaxDuration, d.followUpMaxDuration),
                timers.continueAsNewHistoryThreshold > 0
                        ? timers.continueAsNewHistoryThreshold : d.continueAsNewHistoryThreshold,
                timers.maxAssessmentRounds > 0 ? timers.maxAssessmentRounds : d.maxAssessmentRounds);
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
