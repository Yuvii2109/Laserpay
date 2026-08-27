package com.laserpay.pdei.orchestrator.config;

import com.laserpay.pdei.orchestrator.model.CaseTimers;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Everything tunable about the orchestrator, under {@code pdei.orchestrator}.
 *
 * <p>The {@link Timers} block is resolved once when a workflow is started and travels inside the
 * workflow input (see {@link CaseTimers}). Retuning it therefore affects <em>new</em> cases only:
 * a running case keeps the timings it began with, because changing them mid-flight would make its
 * event history un-replayable.</p>
 */
@ConfigurationProperties(prefix = "pdei.orchestrator")
public class OrchestratorProperties {

    /** Whether {@code DisputeCreated} events start workflows. Off in tests and in read-only replicas. */
    private boolean startWorkflowsFromEvents = true;

    /** Actor recorded on activities the workflow performs without a human. */
    private String defaultActor = "case-orchestrator-service";

    /**
     * Ceiling on one case, end to end, across every continue-as-new generation. Deliberately longer
     * than the 45-day follow-up window plus the 7-day evidence wait.
     */
    private Duration workflowExecutionTimeout = Duration.ofDays(90);

    /** Ceiling on a single workflow task (one slice of decision work). */
    private Duration workflowTaskTimeout = Duration.ofSeconds(20);

    private final Timers timers = new Timers();
    private final Worker worker = new Worker();

    public boolean isStartWorkflowsFromEvents() {
        return startWorkflowsFromEvents;
    }

    public void setStartWorkflowsFromEvents(boolean startWorkflowsFromEvents) {
        this.startWorkflowsFromEvents = startWorkflowsFromEvents;
    }

    public String getDefaultActor() {
        return defaultActor;
    }

    public void setDefaultActor(String defaultActor) {
        this.defaultActor = defaultActor;
    }

    public Duration getWorkflowExecutionTimeout() {
        return workflowExecutionTimeout;
    }

    public void setWorkflowExecutionTimeout(Duration workflowExecutionTimeout) {
        this.workflowExecutionTimeout = workflowExecutionTimeout;
    }

    public Duration getWorkflowTaskTimeout() {
        return workflowTaskTimeout;
    }

    public void setWorkflowTaskTimeout(Duration workflowTaskTimeout) {
        this.workflowTaskTimeout = workflowTaskTimeout;
    }

    public Timers getTimers() {
        return timers;
    }

    public Worker getWorker() {
        return worker;
    }

    /** Snapshot of the configured timers, pinned into a workflow input at start time. */
    public CaseTimers toCaseTimers() {
        return CaseTimers.orDefaults(new CaseTimers(
                timers.getMissingEvidenceWait(),
                timers.getEvidenceWaitSlice(),
                timers.getHumanApprovalTimeout(),
                timers.getEscalationTimeout(),
                timers.getFollowUpInterval(),
                timers.getFollowUpMaxDuration(),
                timers.getContinueAsNewHistoryThreshold(),
                timers.getMaxAssessmentRounds()));
    }

    /** Workflow durations. Defaults mirror {@link CaseTimers#defaults()}. */
    public static class Timers {

        /** Step 4 budget. PLATFORM-CONTRACT section 10 caps this at 7 days. */
        private Duration missingEvidenceWait = CaseTimers.DEFAULT_MISSING_EVIDENCE_WAIT;
        /** How often the step 4 wait wakes to re-evaluate; does not extend the budget. */
        private Duration evidenceWaitSlice = CaseTimers.DEFAULT_EVIDENCE_WAIT_SLICE;
        /** Step 8 first window. On expiry the case emits {@code CaseEscalated}. */
        private Duration humanApprovalTimeout = CaseTimers.DEFAULT_HUMAN_APPROVAL_TIMEOUT;
        /** Step 8 second window, after escalation. On expiry the case closes. */
        private Duration escalationTimeout = CaseTimers.DEFAULT_ESCALATION_TIMEOUT;
        /** Step 11 tick interval. */
        private Duration followUpInterval = CaseTimers.DEFAULT_FOLLOW_UP_INTERVAL;
        /** Step 11 ceiling. */
        private Duration followUpMaxDuration = CaseTimers.DEFAULT_FOLLOW_UP_MAX_DURATION;
        /** Event-history length at which a long wait continues as new. */
        private int continueAsNewHistoryThreshold = CaseTimers.DEFAULT_CONTINUE_AS_NEW_HISTORY_THRESHOLD;
        /** How many times steps 2-8 may repeat on REQUEST_MORE_EVIDENCE. */
        private int maxAssessmentRounds = CaseTimers.DEFAULT_MAX_ASSESSMENT_ROUNDS;

        public Duration getMissingEvidenceWait() {
            return missingEvidenceWait;
        }

        public void setMissingEvidenceWait(Duration missingEvidenceWait) {
            this.missingEvidenceWait = missingEvidenceWait;
        }

        public Duration getEvidenceWaitSlice() {
            return evidenceWaitSlice;
        }

        public void setEvidenceWaitSlice(Duration evidenceWaitSlice) {
            this.evidenceWaitSlice = evidenceWaitSlice;
        }

        public Duration getHumanApprovalTimeout() {
            return humanApprovalTimeout;
        }

        public void setHumanApprovalTimeout(Duration humanApprovalTimeout) {
            this.humanApprovalTimeout = humanApprovalTimeout;
        }

        public Duration getEscalationTimeout() {
            return escalationTimeout;
        }

        public void setEscalationTimeout(Duration escalationTimeout) {
            this.escalationTimeout = escalationTimeout;
        }

        public Duration getFollowUpInterval() {
            return followUpInterval;
        }

        public void setFollowUpInterval(Duration followUpInterval) {
            this.followUpInterval = followUpInterval;
        }

        public Duration getFollowUpMaxDuration() {
            return followUpMaxDuration;
        }

        public void setFollowUpMaxDuration(Duration followUpMaxDuration) {
            this.followUpMaxDuration = followUpMaxDuration;
        }

        public int getContinueAsNewHistoryThreshold() {
            return continueAsNewHistoryThreshold;
        }

        public void setContinueAsNewHistoryThreshold(int continueAsNewHistoryThreshold) {
            this.continueAsNewHistoryThreshold = continueAsNewHistoryThreshold;
        }

        public int getMaxAssessmentRounds() {
            return maxAssessmentRounds;
        }

        public void setMaxAssessmentRounds(int maxAssessmentRounds) {
            this.maxAssessmentRounds = maxAssessmentRounds;
        }
    }

    /**
     * Temporal worker tuning.
     *
     * <p>Activities outnumber workflow tasks in this workload: a case spends its time waiting, and
     * when it does work that work is an activity. Hence the wider activity pool.</p>
     */
    public static class Worker {

        private int maxConcurrentWorkflowTaskExecutors = 20;
        private int maxConcurrentActivityExecutors = 60;
        private int maxConcurrentLocalActivityExecutors = 20;
        /** Sticky-cache size: how many workflow executions stay in memory between tasks. */
        private int workflowCacheSize = 200;
        /** Grace period given to in-flight activities when the worker shuts down. */
        private Duration shutdownTimeout = Duration.ofSeconds(30);

        public int getMaxConcurrentWorkflowTaskExecutors() {
            return maxConcurrentWorkflowTaskExecutors;
        }

        public void setMaxConcurrentWorkflowTaskExecutors(int maxConcurrentWorkflowTaskExecutors) {
            this.maxConcurrentWorkflowTaskExecutors = maxConcurrentWorkflowTaskExecutors;
        }

        public int getMaxConcurrentActivityExecutors() {
            return maxConcurrentActivityExecutors;
        }

        public void setMaxConcurrentActivityExecutors(int maxConcurrentActivityExecutors) {
            this.maxConcurrentActivityExecutors = maxConcurrentActivityExecutors;
        }

        public int getMaxConcurrentLocalActivityExecutors() {
            return maxConcurrentLocalActivityExecutors;
        }

        public void setMaxConcurrentLocalActivityExecutors(int maxConcurrentLocalActivityExecutors) {
            this.maxConcurrentLocalActivityExecutors = maxConcurrentLocalActivityExecutors;
        }

        public int getWorkflowCacheSize() {
            return workflowCacheSize;
        }

        public void setWorkflowCacheSize(int workflowCacheSize) {
            this.workflowCacheSize = workflowCacheSize;
        }

        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
        }
    }
}
