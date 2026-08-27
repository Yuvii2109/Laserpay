package com.laserpay.pdei.audit.retention;

import com.laserpay.pdei.audit.config.AuditProperties;
import com.laserpay.pdei.audit.repository.AuditEventStore;
import com.laserpay.pdei.audit.repository.AuditQuery;
import com.laserpay.pdei.common.time.Clocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Audit retention: documented, evaluated, and <strong>non-destructive by default</strong>.
 *
 * <h2>Why this class deletes nothing</h2>
 *
 * <p>{@code pdei.audit_events} is a hash chain. Entry <em>n</em> stores the hash of entry
 * <em>n-1</em>, and its own hash covers that link. Delete an old entry and every hash after it stops
 * verifying: {@code GET /chain/verify} reports a BROKEN_LINK divergence at the deletion point and
 * cannot distinguish "we pruned this legitimately" from "someone destroyed evidence". A chain that
 * routinely reports itself broken is a chain nobody checks, which defeats the only reason to build
 * one.
 *
 * <p>{@code V8__audit.sql} agrees, and enforces it below the application:
 * {@code trg_audit_events_immutable} rejects UPDATE and DELETE on the table outright. Setting
 * {@code pdei.audit.retention.enabled=true} would therefore <em>still</em> fail at the database - by
 * design. Destruction is not one config flag away; it would take a migration, and that migration
 * should be hard to write.
 *
 * <p>So this job runs on a schedule and produces a {@link RetentionReport}: how much history exists,
 * how much of it is older than the retention floor, per chain. That is the input to the decisions
 * worth actually making - archive to cold storage, or budget for the growth.
 *
 * <h2>What a real implementation would require</h2>
 *
 * <p>If entries must genuinely leave the live table, the chain has to survive it. The two designs
 * that work, neither of which is a delete:
 *
 * <ol>
 *   <li><strong>Checkpoint and archive.</strong> Export the prefix to be retired (the NDJSON export
 *       already emits exactly this), record a signed checkpoint entry naming the archived range and
 *       its final hash, then start a fresh chain whose genesis {@code previousHash} is that
 *       checkpoint. Verification of the live chain succeeds from the checkpoint forward; the archive
 *       verifies independently. Nothing is unprovable, only relocated.</li>
 *   <li><strong>Redaction with tombstones.</strong> For a right-to-erasure request, replace the
 *       {@code before}/{@code after} payloads with a tombstone whose hash preimage is preserved, so
 *       the chain still verifies while the personal data is gone. This needs a schema change: the
 *       hash preimage must be storable separately from the content.</li>
 * </ol>
 *
 * <p>Retention floor defaults to 2555 days (seven years), which covers the usual card-scheme
 * evidence retention obligations with margin. Shortening it is a compliance decision, not a
 * capacity one.
 */
@Component
public class RetentionPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicy.class);

    private static final int MAX_CHAINS_PER_REPORT = 5000;

    private final AuditEventStore store;
    private final AuditProperties properties;
    private final Clocks clock;

    private volatile RetentionReport lastReport;

    public RetentionPolicy(AuditEventStore store, AuditProperties properties, Clocks clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Scheduled evaluation. Reports; does not delete. */
    @Scheduled(cron = "${pdei.audit.retention.report-cron:0 45 3 * * *}",
            zone = "${pdei.audit.retention.zone:UTC}")
    public void scheduledEvaluation() {
        try {
            RetentionReport report = evaluate();
            if (report.hasEligibleEntries()) {
                log.info("audit retention: {} of {} entries are older than {} days (cutoff {});"
                                + " nothing was removed - see RetentionPolicy for why",
                        report.eligibleEntries(), report.totalEntries(), report.retainDays(),
                        report.cutoff());
            } else {
                log.debug("audit retention: no entries older than {} days", report.retainDays());
            }
        } catch (RuntimeException e) {
            log.error("audit retention evaluation failed: {}", e.toString(), e);
        }
    }

    /**
     * Evaluate retention across every chain.
     *
     * <p>Read-only. The {@code destroyed} count in the returned report is always zero unless
     * destruction has been explicitly enabled <em>and</em> the database trigger has been removed,
     * which is deliberately a two-step, reviewable change.
     */
    public RetentionReport evaluate() {
        AuditProperties.Retention config = properties.getRetention();
        Instant now = clock.now();
        Instant cutoff = now.minus(Duration.ofDays(Math.max(1, config.getRetainDays())));

        List<RetentionReport.ChainSummary> chains = new ArrayList<>();
        long total = 0L;
        long eligible = 0L;

        for (String merchantId : store.findChainKeys(MAX_CHAINS_PER_REPORT)) {
            long entries = store.countChain(merchantId);
            long old = store.count(new AuditQuery(null, null, merchantId, null, null, null, cutoff, 0, 1));
            Instant[] bounds = store.chainBounds(merchantId);

            chains.add(new RetentionReport.ChainSummary(merchantId, entries, old, bounds[0], bounds[1]));
            total += entries;
            eligible += old;
        }

        RetentionReport report = new RetentionReport(now, cutoff, config.getRetainDays(),
                isDryRun(config), total, eligible, destroy(config, eligible), chains);
        this.lastReport = report;
        return report;
    }

    /**
     * The destruction step, which deliberately does nothing.
     *
     * <p>Kept as a named method rather than omitted so that the decision is visible in the code: a
     * future author looking for "where does retention delete things" finds this, and the reasons,
     * instead of finding nothing and assuming it was forgotten.
     */
    private long destroy(AuditProperties.Retention config, long eligible) {
        if (!config.isEnabled() || config.isDryRun()) {
            return 0L;
        }
        log.error("audit retention destruction is configured (enabled=true, dryRun=false) but is NOT"
                        + " implemented: {} entries would be affected. Deleting from a hash chain breaks"
                        + " verification, and V8__audit.sql installs a trigger that rejects DELETE."
                        + " Use the checkpoint-and-archive design documented on RetentionPolicy.",
                eligible);
        return 0L;
    }

    private static boolean isDryRun(AuditProperties.Retention config) {
        return !config.isEnabled() || config.isDryRun();
    }

    /** The most recent evaluation, or null before the first run. */
    public RetentionReport lastReport() {
        return lastReport;
    }
}
