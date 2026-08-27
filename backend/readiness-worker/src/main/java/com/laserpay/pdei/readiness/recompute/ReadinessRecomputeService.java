package com.laserpay.pdei.readiness.recompute;

import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.readiness.ReadinessEngine;
import com.laserpay.pdei.readiness.config.ReadinessProperties;
import com.laserpay.pdei.readiness.metrics.ReadinessWorkerMetrics;
import com.laserpay.pdei.readiness.persistence.ReadinessStore;
import com.laserpay.pdei.readiness.publish.ReadinessEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One readiness recomputation, end to end.
 *
 * <pre>
 *   pdei:lock:readiness:{txId}   ->  ReadinessEngine.compute
 *                                ->  readiness_snapshots + readiness_gaps
 *                                ->  transactions.readiness_* projection
 *                                ->  pdei:readiness:{txId}  (10 min)
 *                                ->  ReadinessRecomputed / ReadinessGapDetected
 *                                ->  audit entry
 * </pre>
 *
 * <p><strong>The lock is the cross-process half of the debounce.</strong>
 * {@link RecomputeDebouncer} collapses a burst inside one JVM; this lock collapses the same burst
 * across replicas. It is taken for the duration of the computation and released in a finally block.
 * Losing the race is not an error and is not retried: whoever holds the lock is computing the same
 * transaction from the same database, so its answer will be the answer this call would have
 * produced. Readiness is deterministic, which is exactly what makes discarding the duplicate safe.
 *
 * <p><strong>Order of operations matters.</strong> Persist, then project, then cache, then publish.
 * A subscriber that reacts to {@code ReadinessRecomputed} by reading the API must never find a
 * score older than the event that woke it.
 *
 * <p>A missing transaction is not a failure. Evidence and entity events routinely arrive before
 * state-builder-worker has created the transaction row (assume late and out-of-order delivery,
 * rule 10); the computation is skipped and the next event - or the at-risk scanner's staleness
 * refresh - picks it up.
 */
public class ReadinessRecomputeService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessRecomputeService.class);

    private static final String AUDIT_ENTITY_TYPE = AggregateType.TRANSACTION.name();
    private static final String AUDIT_ACTION = "READINESS_RECOMPUTED";

    private final ReadinessEngine engine;
    private final ReadinessStore store;
    private final ReadinessCache cache;
    private final ReadinessEventPublisher publisher;
    private final RecomputeLock lock;
    private final ReadinessProperties properties;
    private final ReadinessWorkerMetrics metrics;

    public ReadinessRecomputeService(ReadinessEngine engine, ReadinessStore store, ReadinessCache cache,
                                     ReadinessEventPublisher publisher, RecomputeLock lock,
                                     ReadinessProperties properties, ReadinessWorkerMetrics metrics) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.lock = lock;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = metrics;
    }

    /** Full Redis key guarding a transaction: {@code pdei:lock:readiness:{transactionId}}. */
    public static String lockKey(String transactionId) {
        return RecomputeLock.key(transactionId);
    }

    /**
     * Recompute one transaction under the distributed lock.
     *
     * @return the snapshot written, or empty when this recomputation was coalesced into another
     *     worker's, the transaction does not exist yet, or the computation failed
     */
    public Optional<ReadinessSnapshot> recompute(RecomputeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String transactionId = request.transactionId();

        RecomputeLock.Acquisition acquisition = lock == null
                ? RecomputeLock.Acquisition.unavailable()
                : lock.acquire(transactionId, lockTtl(), properties.getLockAttempts(), lockBackoff());

        if (acquisition.isDuplicate()) {
            // Another replica is already scoring this transaction from the same state.
            if (metrics != null) {
                metrics.recomputeLockContended();
            }
            log.debug("readiness recompute coalesced, lock held elsewhere: transactionId={}", transactionId);
            return Optional.empty();
        }

        try {
            return Optional.of(computeAndRecord(request));
        } catch (NotFoundException e) {
            log.debug("readiness recompute skipped, transaction not materialised yet: transactionId={}",
                    transactionId);
            // The score would be meaningless without the transaction; drop any stale cached value
            // so nobody serves an answer we can no longer justify.
            cache.evict(transactionId);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.error("readiness recompute failed: transactionId={} trigger={}: {}",
                    transactionId, request.trigger(), e.toString(), e);
            return Optional.empty();
        } finally {
            if (lock != null) {
                lock.release(transactionId, acquisition.token());
            }
        }
    }

    private ReadinessSnapshot computeAndRecord(RecomputeRequest request) {
        String transactionId = request.transactionId();

        // Deterministic scoring (PLATFORM-CONTRACT section 7). The engine also records
        // pdei_readiness_computation_seconds and pdei_readiness_score{merchant}.
        ReadinessSnapshot snapshot = engine.compute(transactionId, request.reasonCode());

        store.write(snapshot, request.trigger(), request.triggerEventId());

        if (properties.isUpdateTransactionProjection()) {
            store.updateTransactionProjection(transactionId, snapshot.score(), snapshot.band(),
                    snapshot.computedAt());
        }

        cache.put(snapshot);

        publisher.publishRecomputed(snapshot, request.trigger(), request.triggerEventId(),
                request.correlationId());

        List<ReadinessGap> notifiable = notifiableGaps(snapshot);
        if (properties.isPublishGapEvents() && !notifiable.isEmpty()) {
            publisher.publishGapDetected(snapshot, notifiable, request.triggerEventId(),
                    request.correlationId());
        }

        publisher.publishAudit(AUDIT_ENTITY_TYPE, transactionId, snapshot.merchantId(), AUDIT_ACTION,
                null, auditPayload(snapshot, request), request.correlationId());

        log.debug("readiness transactionId={} score={} band={} gaps={} trigger={}",
                transactionId, snapshot.score(), snapshot.band(), snapshot.gaps().size(),
                request.trigger());
        return snapshot;
    }

    /**
     * Gaps worth waking someone up for.
     *
     * <p>Filtered by configured severity so the readiness topic carries signal: every transaction
     * has a LOW gap somewhere, and a feed that fires on all of them is a feed nobody reads.
     */
    private List<ReadinessGap> notifiableGaps(ReadinessSnapshot snapshot) {
        GapSeverity floor = properties.getGapEventMinSeverity();
        if (floor == null) {
            return snapshot.gaps();
        }
        return snapshot.gaps().stream()
                .filter(gap -> gap.severity() != null && gap.severity().ordinal() >= floor.ordinal())
                .toList();
    }

    /** Compact "after" state for the audit trail: the decision, not the whole document. */
    private static Map<String, Object> auditPayload(ReadinessSnapshot snapshot, RecomputeRequest request) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("snapshotId", snapshot.snapshotId());
        after.put("score", snapshot.score());
        after.put("band", snapshot.band() == null ? null : snapshot.band().name());
        after.put("baseScore", snapshot.baseScore());
        after.put("penaltyPoints", snapshot.penaltyPoints());
        after.put("gapCount", snapshot.gaps().size());
        after.put("contradictionCount", snapshot.contradictions().size());
        after.put("policyVersionId", snapshot.policyVersionId());
        after.put("trigger", request.trigger() == null ? null : request.trigger().name());
        after.put("triggerEventId", request.triggerEventId());
        return after;
    }

    private Duration lockTtl() {
        Duration ttl = properties.getLockTtl();
        return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofSeconds(30) : ttl;
    }

    private Duration lockBackoff() {
        Duration backoff = properties.getLockBackoff();
        return backoff == null || backoff.isNegative() ? Duration.ofMillis(50) : backoff;
    }
}
