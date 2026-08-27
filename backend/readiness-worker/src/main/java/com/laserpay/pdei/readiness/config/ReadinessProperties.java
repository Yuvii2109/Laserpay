package com.laserpay.pdei.readiness.config;

import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.ReadinessBand;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Behaviour of the readiness worker itself, bound from the {@code pdei.readiness} prefix.
 *
 * <p>Deliberately separate from {@code pdei.core.readiness} ({@code CoreProperties}), which
 * configures the scoring <em>engine</em>. This class configures the <em>process</em> around it:
 * how long to debounce, how long to hold the lock, when to sweep, how often to scan.
 *
 * <p>Every default is the value the platform contract implies, so an empty environment still
 * behaves as documented.
 */
@ConfigurationProperties(prefix = "pdei.readiness")
public class ReadinessProperties {

    /**
     * Burst absorption window. Events for the same transaction arriving inside this window collapse
     * into a single recomputation. A payment capture that fans out into five evidence artifacts
     * should cost one score, not five.
     */
    private Duration debounce = Duration.ofSeconds(2);

    /**
     * Hard ceiling on deferral. Under a continuous event stream the debounce window would otherwise
     * keep sliding forever and readiness would never refresh.
     */
    private Duration maxDebounceDelay = Duration.ofSeconds(30);

    /** TTL of {@code pdei:lock:readiness:{transactionId}} (PLATFORM-CONTRACT section 12). */
    private Duration lockTtl = Duration.ofSeconds(30);

    /** Lock acquisition attempts. One attempt means a contended transaction is simply coalesced. */
    private int lockAttempts = 1;

    private Duration lockBackoff = Duration.ofMillis(50);

    /** Threads that execute recomputations, off the Kafka listener threads. */
    private int workerThreads = 4;

    /** Bounded backlog of pending recomputations; overflow degrades to caller-runs. */
    private int queueCapacity = 10_000;

    /** TTL of the Redis fast-path dedupe key {@code pdei:idem:{eventId}}. */
    private Duration idempotencyTtl = Duration.ofDays(7);

    /** Publish {@code ReadinessGapDetected} alongside {@code ReadinessRecomputed}. */
    private boolean publishGapEvents = true;

    /** Only gaps at or above this severity raise a {@code ReadinessGapDetected} event. */
    private GapSeverity gapEventMinSeverity = GapSeverity.HIGH;

    /** Maintain the denormalised readiness projection on {@code pdei.transactions}. */
    private boolean updateTransactionProjection = true;

    private final Sweep sweep = new Sweep();
    private final AtRisk atRisk = new AtRisk();

    public Duration getDebounce() {
        return debounce;
    }

    public void setDebounce(Duration debounce) {
        this.debounce = debounce;
    }

    public Duration getMaxDebounceDelay() {
        return maxDebounceDelay;
    }

    public void setMaxDebounceDelay(Duration maxDebounceDelay) {
        this.maxDebounceDelay = maxDebounceDelay;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public int getLockAttempts() {
        return lockAttempts;
    }

    public void setLockAttempts(int lockAttempts) {
        this.lockAttempts = lockAttempts;
    }

    public Duration getLockBackoff() {
        return lockBackoff;
    }

    public void setLockBackoff(Duration lockBackoff) {
        this.lockBackoff = lockBackoff;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public boolean isPublishGapEvents() {
        return publishGapEvents;
    }

    public void setPublishGapEvents(boolean publishGapEvents) {
        this.publishGapEvents = publishGapEvents;
    }

    public GapSeverity getGapEventMinSeverity() {
        return gapEventMinSeverity;
    }

    public void setGapEventMinSeverity(GapSeverity gapEventMinSeverity) {
        this.gapEventMinSeverity = gapEventMinSeverity;
    }

    public boolean isUpdateTransactionProjection() {
        return updateTransactionProjection;
    }

    public void setUpdateTransactionProjection(boolean updateTransactionProjection) {
        this.updateTransactionProjection = updateTransactionProjection;
    }

    public Sweep getSweep() {
        return sweep;
    }

    public AtRisk getAtRisk() {
        return atRisk;
    }

    /** Nightly evidence expiry sweep, implemented by {@code readiness.sweep.ExpirySweepJob}. */
    public static class Sweep {

        private boolean enabled = true;

        /** Spring cron expression; default nightly at 02:15. */
        private String cron = "0 15 2 * * *";

        /** Timezone the cron is evaluated in. Always UTC in this platform. */
        private String zone = "UTC";

        /** Rows per batch. */
        private int batchSize = 500;

        /** Safety valve: at most this many batches per run, so one sweep cannot run unbounded. */
        private int maxBatches = 20;

        /** Evidence expiring inside this window transitions ACTIVE to EXPIRING (contract section 7). */
        private int warningDays = 7;

        /** Enqueue a readiness recomputation for every transaction touched by the sweep. */
        private boolean recomputeAffected = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxBatches() {
            return maxBatches;
        }

        public void setMaxBatches(int maxBatches) {
            this.maxBatches = maxBatches;
        }

        public int getWarningDays() {
            return warningDays;
        }

        public void setWarningDays(int warningDays) {
            this.warningDays = warningDays;
        }

        public boolean isRecomputeAffected() {
            return recomputeAffected;
        }

        public void setRecomputeAffected(boolean recomputeAffected) {
            this.recomputeAffected = recomputeAffected;
        }
    }

    /** Periodic at-risk feed materialisation, implemented by {@code readiness.sweep.AtRiskScanner}. */
    public static class AtRisk {

        private boolean enabled = true;

        private Duration interval = Duration.ofMinutes(5);

        private Duration initialDelay = Duration.ofMinutes(1);

        /** Rows materialised per scan. */
        private int limit = 500;

        /** A current snapshot older than this is refreshed by the scanner. */
        private Duration staleAfter = Duration.ofHours(6);

        /** Bands considered at risk (PLATFORM-CONTRACT section 6). */
        private List<ReadinessBand> bands = List.of(ReadinessBand.AT_RISK, ReadinessBand.NOT_READY);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getStaleAfter() {
            return staleAfter;
        }

        public void setStaleAfter(Duration staleAfter) {
            this.staleAfter = staleAfter;
        }

        public List<ReadinessBand> getBands() {
            return bands;
        }

        public void setBands(List<ReadinessBand> bands) {
            this.bands = bands == null || bands.isEmpty()
                    ? List.of(ReadinessBand.AT_RISK, ReadinessBand.NOT_READY)
                    : List.copyOf(bands);
        }

        /** Band names as stored in {@code readiness_snapshots.band}. */
        public Set<ReadinessBand> bandSet() {
            return bands.isEmpty() ? EnumSet.noneOf(ReadinessBand.class) : EnumSet.copyOf(bands);
        }
    }
}
