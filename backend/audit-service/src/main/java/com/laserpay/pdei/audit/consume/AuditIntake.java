package com.laserpay.pdei.audit.consume;

import com.laserpay.pdei.audit.chain.AuditChainAppender;
import com.laserpay.pdei.audit.metrics.AuditMetrics;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.metrics.MetricNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * What both consumers do with a record: claim it, turn it into an audit entry, append it.
 *
 * <p>Two inbound shapes reach this service and they are handled differently on purpose:
 *
 * <ul>
 *   <li><strong>{@code AuditEvent} on {@code pdei.audit.events.v1}</strong> - a service explicitly
 *       reporting a state change it made, with its own {@code before}/{@code after} snapshots. Used
 *       as given (subject to re-sealing), because the producer knows things about its own change
 *       that no derived record could reconstruct.</li>
 *   <li><strong>{@code CanonicalEvent} on every domain topic</strong> - the facts themselves.
 *       Mapped mechanically by {@link CanonicalAuditMapper}, so the trail is complete even for
 *       actions nobody remembered to report explicitly.</li>
 * </ul>
 *
 * <p>The two overlap: a service that both reports an audit entry and publishes a domain event
 * produces two entries for one change. That is intentional and not a duplicate - they record
 * different things (the intent, and the fact) with different audit ids and different content, and a
 * trail that recorded only one of them would be answering a different question than the one an
 * auditor asks.
 */
public class AuditIntake {

    private static final Logger log = LoggerFactory.getLogger(AuditIntake.class);

    private final IdempotencyGuard idempotency;
    private final AuditChainAppender appender;
    private final AuditMetrics metrics;

    public AuditIntake(IdempotencyGuard idempotency, AuditChainAppender appender, AuditMetrics metrics) {
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
        this.appender = Objects.requireNonNull(appender, "appender must not be null");
        this.metrics = metrics;
    }

    /** Handle an explicitly reported audit entry from {@code pdei.audit.events.v1}. */
    public Outcome acceptAuditEvent(AuditEvent event) {
        if (event == null) {
            return Outcome.SKIPPED;
        }
        long startNanos = System.nanoTime();
        String action = event.action();
        try {
            if (!idempotency.claim(event.auditId())) {
                record(action, MetricNames.Outcome.DUPLICATE, startNanos);
                return Outcome.DUPLICATE;
            }
            appender.append(event);
            record(action, MetricNames.Outcome.SUCCESS, startNanos);
            return Outcome.APPENDED;
        } catch (ValidationException e) {
            return rejected(action, event.auditId(), e, startNanos);
        } catch (RuntimeException e) {
            record(action, MetricNames.Outcome.FAILURE, startNanos);
            throw e;
        }
    }

    /** Derive and append an audit entry for a domain event. */
    public Outcome acceptDomainEvent(CanonicalEvent event) {
        if (event == null || event.eventType() == null) {
            return Outcome.SKIPPED;
        }
        long startNanos = System.nanoTime();
        String type = event.eventType().name();
        try {
            if (!idempotency.claim(event.eventId())) {
                record(type, MetricNames.Outcome.DUPLICATE, startNanos);
                return Outcome.DUPLICATE;
            }
            appender.append(CanonicalAuditMapper.toAuditEvent(event));
            record(type, MetricNames.Outcome.SUCCESS, startNanos);
            return Outcome.APPENDED;
        } catch (ValidationException e) {
            return rejected(type, event.eventId(), e, startNanos);
        } catch (RuntimeException e) {
            record(type, MetricNames.Outcome.FAILURE, startNanos);
            throw e;
        }
    }

    /**
     * A record that cannot legally be stored.
     *
     * <p>Rethrown so the listener dead-letters it: the record is preserved for replay and the
     * producer's contract violation is visible, which is strictly better than this service editing
     * an audit record until it fits.
     */
    private Outcome rejected(String type, String id, ValidationException failure, long startNanos) {
        record(type, MetricNames.Outcome.FAILURE, startNanos);
        if (metrics != null) {
            metrics.rejected(failure.getClass().getSimpleName());
        }
        log.warn("audit record {} rejected as unstorable: {}", id, failure.getMessage());
        throw failure;
    }

    private void record(String type, String outcome, long startNanos) {
        if (metrics == null) {
            return;
        }
        metrics.eventProcessed(type, outcome);
        metrics.eventLatency(type, System.nanoTime() - startNanos);
    }

    /** What the intake did with a record. */
    public enum Outcome {
        /** Appended to a merchant chain. */
        APPENDED,
        /** Already recorded by this consumer group. */
        DUPLICATE,
        /** Nothing to record. */
        SKIPPED
    }
}
