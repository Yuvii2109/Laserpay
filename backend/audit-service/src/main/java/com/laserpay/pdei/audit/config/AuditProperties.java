package com.laserpay.pdei.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Behaviour of the audit service, bound from the {@code pdei.audit} prefix.
 *
 * <p>Distinct from {@code pdei.core.audit} ({@code CoreProperties.Audit}), which configures
 * {@code evidence-core}'s {@code AuditRecorder} - the client-side helper other services use to
 * <em>report</em> audit entries. This class configures the <em>sink</em>: how the chain is locked,
 * how many times an append retries a lost race, what the read API will serve, and what the
 * retention job is allowed to do (by default: nothing destructive).
 */
@ConfigurationProperties(prefix = "pdei.audit")
public class AuditProperties {

    /** TTL of {@code pdei:lock:audit:{merchantId}} (PLATFORM-CONTRACT section 12). */
    private Duration chainLockTtl = Duration.ofSeconds(30);

    private int chainLockAttempts = 5;

    private Duration chainLockBackoff = Duration.ofMillis(50);

    /**
     * Retries when the {@code ux_audit_events_link} unique index rejects an append because another
     * writer claimed the same predecessor. The database, not the lock, is the real serialisation
     * point; the lock only makes the collision rare.
     */
    private int appendMaxAttempts = 5;

    /** TTL of the Redis fast-path dedupe key {@code pdei:idem:{eventId}}. */
    private Duration idempotencyTtl = Duration.ofDays(7);

    /**
     * Store a producer-supplied {@code hash} verbatim when it both verifies against its own content
     * and links to this chain's current head. Turning this off makes the service re-seal every
     * entry, which renumbers producer hashes but guarantees a single authority over the chain.
     */
    private boolean acceptPresealedLinks = true;

    private final Consume consume = new Consume();
    private final Api api = new Api();
    private final Retention retention = new Retention();

    public Duration getChainLockTtl() {
        return chainLockTtl;
    }

    public void setChainLockTtl(Duration chainLockTtl) {
        this.chainLockTtl = chainLockTtl;
    }

    public int getChainLockAttempts() {
        return chainLockAttempts;
    }

    public void setChainLockAttempts(int chainLockAttempts) {
        this.chainLockAttempts = chainLockAttempts;
    }

    public Duration getChainLockBackoff() {
        return chainLockBackoff;
    }

    public void setChainLockBackoff(Duration chainLockBackoff) {
        this.chainLockBackoff = chainLockBackoff;
    }

    public int getAppendMaxAttempts() {
        return appendMaxAttempts;
    }

    public void setAppendMaxAttempts(int appendMaxAttempts) {
        this.appendMaxAttempts = appendMaxAttempts;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public boolean isAcceptPresealedLinks() {
        return acceptPresealedLinks;
    }

    public void setAcceptPresealedLinks(boolean acceptPresealedLinks) {
        this.acceptPresealedLinks = acceptPresealedLinks;
    }

    public Consume getConsume() {
        return consume;
    }

    public Api getApi() {
        return api;
    }

    public Retention getRetention() {
        return retention;
    }

    /**
     * Per-topic consumption switches.
     *
     * <p>Useful for a targeted replay: disable everything except the topic being replayed so the
     * chain grows only with the entries under investigation.
     */
    public static class Consume {

        private boolean auditTopic = true;
        private boolean canonicalEvents = true;
        private boolean evidenceEvents = true;
        private boolean readinessEvents = true;
        private boolean disputeEvents = true;
        private boolean caseEvents = true;

        public boolean isAuditTopic() {
            return auditTopic;
        }

        public void setAuditTopic(boolean auditTopic) {
            this.auditTopic = auditTopic;
        }

        public boolean isCanonicalEvents() {
            return canonicalEvents;
        }

        public void setCanonicalEvents(boolean canonicalEvents) {
            this.canonicalEvents = canonicalEvents;
        }

        public boolean isEvidenceEvents() {
            return evidenceEvents;
        }

        public void setEvidenceEvents(boolean evidenceEvents) {
            this.evidenceEvents = evidenceEvents;
        }

        public boolean isReadinessEvents() {
            return readinessEvents;
        }

        public void setReadinessEvents(boolean readinessEvents) {
            this.readinessEvents = readinessEvents;
        }

        public boolean isDisputeEvents() {
            return disputeEvents;
        }

        public void setDisputeEvents(boolean disputeEvents) {
            this.disputeEvents = disputeEvents;
        }

        public boolean isCaseEvents() {
            return caseEvents;
        }

        public void setCaseEvents(boolean caseEvents) {
            this.caseEvents = caseEvents;
        }
    }

    /** Limits on the read API of contract section 8.4. */
    public static class Api {

        private int defaultPageSize = 50;

        private int maxPageSize = 500;

        /** Upper bound on how many entries one {@code /chain/verify} call may walk. */
        private int maxVerifyEvents = 100_000;

        /** Rows fetched per round trip while streaming {@code /export}. */
        private int exportBatchSize = 500;

        /** Upper bound on one export, so a single request cannot stream the whole database forever. */
        private int maxExportEvents = 1_000_000;

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int maxPageSize) {
            this.maxPageSize = maxPageSize;
        }

        public int getMaxVerifyEvents() {
            return maxVerifyEvents;
        }

        public void setMaxVerifyEvents(int maxVerifyEvents) {
            this.maxVerifyEvents = maxVerifyEvents;
        }

        public int getExportBatchSize() {
            return exportBatchSize;
        }

        public void setExportBatchSize(int exportBatchSize) {
            this.exportBatchSize = exportBatchSize;
        }

        public int getMaxExportEvents() {
            return maxExportEvents;
        }

        public void setMaxExportEvents(int maxExportEvents) {
            this.maxExportEvents = maxExportEvents;
        }
    }

    /**
     * Retention.
     *
     * <p>{@code enabled=false} and {@code dryRun=true} are the defaults and are meant to stay that
     * way: pruning a hash chain breaks it by construction. See
     * {@code com.laserpay.pdei.audit.retention.RetentionPolicy} for what would have to be true
     * before anything is ever removed.
     */
    public static class Retention {

        private boolean enabled = false;

        private boolean dryRun = true;

        /** Entries younger than this are never candidates. Default: seven years. */
        private int retainDays = 2555;

        private String reportCron = "0 45 3 * * *";

        private String zone = "UTC";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public int getRetainDays() {
            return retainDays;
        }

        public void setRetainDays(int retainDays) {
            this.retainDays = retainDays;
        }

        public String getReportCron() {
            return reportCron;
        }

        public void setReportCron(String reportCron) {
            this.reportCron = reportCron;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }
    }
}
