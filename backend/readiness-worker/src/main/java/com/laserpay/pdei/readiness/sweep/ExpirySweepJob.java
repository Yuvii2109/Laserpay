package com.laserpay.pdei.readiness.sweep;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.readiness.config.ReadinessProperties;
import com.laserpay.pdei.readiness.metrics.ReadinessWorkerMetrics;
import com.laserpay.pdei.readiness.persistence.EvidenceExpiryStore;
import com.laserpay.pdei.readiness.persistence.EvidenceExpiryStore.ExpiringEvidence;
import com.laserpay.pdei.readiness.publish.ReadinessEventPublisher;
import com.laserpay.pdei.readiness.recompute.RecomputeDebouncer;
import com.laserpay.pdei.readiness.recompute.RecomputeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The scheduled evidence expiry sweep (PLATFORM-CONTRACT section 7: "a nightly sweep for expiry
 * transitions").
 *
 * <p>Evidence rots. A delivery photograph a carrier retains for ninety days, a policy document
 * superseded by a new version, a signed contract past its retention window - each stops being
 * usable at a moment nobody sends an event about. This job is what turns the passage of time into
 * an event the rest of the platform can react to.
 *
 * <pre>
 *   pass 1  expires_at &lt;= now                     PENDING|ACTIVE|EXPIRING -&gt; EXPIRED   + EvidenceExpired
 *   pass 2  now &lt; expires_at &lt;= now + warningDays  ACTIVE                  -&gt; EXPIRING
 *   pass 3  every touched transaction              -&gt; debounced recomputation (NIGHTLY_SWEEP)
 * </pre>
 *
 * <p>The EXPIRING pass is what makes the platform proactive rather than forensic: the readiness
 * engine applies its -5 EXPIRING_SOON penalty (contract section 7) while the merchant can still do
 * something about it, days before the artifact actually dies.
 *
 * <p><strong>Idempotent by construction.</strong> Every transition is conditional on the row's
 * current status, so running the sweep twice, or running it on two replicas at once, transitions
 * each artifact exactly once. Batches are bounded and the run stops as soon as a batch changes
 * nothing, so a row that refuses to transition cannot spin the job.
 *
 * <p>The recomputations are <em>enqueued</em>, not executed inline: a sweep that expired four
 * thousand artifacts across nine hundred transactions must not hold a database connection while it
 * rescores all of them. The debouncer also collapses the sweep's own request with the one triggered
 * by the {@code EvidenceExpired} event it just published, so each transaction is scored once.
 */
@Component
public class ExpirySweepJob {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweepJob.class);

    private static final String AUDIT_ENTITY_TYPE = AggregateType.EVIDENCE.name();
    private static final String ACTION_EXPIRED = "EVIDENCE_EXPIRED";
    private static final String ACTION_EXPIRING = "EVIDENCE_EXPIRING";
    private static final String REASON = "retention window elapsed (readiness expiry sweep)";

    private final EvidenceExpiryStore store;
    private final ReadinessEventPublisher publisher;
    private final RecomputeDebouncer debouncer;
    private final ReadinessProperties properties;
    private final ReadinessWorkerMetrics metrics;
    private final Clocks clock;

    public ExpirySweepJob(EvidenceExpiryStore store, ReadinessEventPublisher publisher,
                          RecomputeDebouncer debouncer, ReadinessProperties properties,
                          ReadinessWorkerMetrics metrics, Clocks clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.debouncer = Objects.requireNonNull(debouncer, "debouncer must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = metrics;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Scheduled entry point. Cron and timezone are configurable
     * ({@code pdei.readiness.sweep.cron}, default {@code 0 15 2 * * *} in UTC); the job can be
     * disabled entirely with {@code pdei.readiness.sweep.enabled=false}.
     */
    @Scheduled(cron = "${pdei.readiness.sweep.cron:0 15 2 * * *}",
            zone = "${pdei.readiness.sweep.zone:UTC}")
    public void scheduledSweep() {
        if (!properties.getSweep().isEnabled()) {
            log.debug("expiry sweep is disabled");
            return;
        }
        SweepResult result = sweep();
        log.info("expiry sweep finished: expired={} markedExpiring={} transactionsQueued={} in {}ms",
                result.expired(), result.markedExpiring(), result.transactionsQueued(),
                result.duration().toMillis());
    }

    /**
     * Run one sweep now.
     *
     * <p>Public and returning its counts so the job can be invoked directly by tests, by the
     * simulator's chaos console ({@code ChaosType.EXPIRE_EVIDENCE}) and by an operator runbook,
     * not only by the scheduler.
     */
    public SweepResult sweep() {
        ReadinessProperties.Sweep config = properties.getSweep();
        Instant startedAt = clock.now();
        Instant warningWindowEnd = startedAt.plus(Duration.ofDays(Math.max(1, config.getWarningDays())));

        Map<String, String> affected = new LinkedHashMap<>();
        int expired = expirePass(startedAt, config, affected);
        int markedExpiring = warningPass(startedAt, warningWindowEnd, config, affected);
        int queued = queueRecomputations(config, affected, startedAt);

        return new SweepResult(expired, markedExpiring, queued, startedAt, clock.now());
    }

    /** Pass 1: everything already past its expiry becomes EXPIRED and announces itself. */
    private int expirePass(Instant now, ReadinessProperties.Sweep config, Map<String, String> affected) {
        int expired = 0;
        for (int batch = 0; batch < Math.max(1, config.getMaxBatches()); batch++) {
            List<ExpiringEvidence> due = store.findDueForExpiry(now, config.getBatchSize());
            if (due.isEmpty()) {
                break;
            }
            int transitionedInBatch = 0;
            for (ExpiringEvidence evidence : due) {
                if (!store.transition(evidence.evidenceId(), EvidenceExpiryStore.EXPIRABLE,
                        EvidenceStatus.EXPIRED, now)) {
                    // Someone else moved it first. Not an error: the sweep is idempotent.
                    continue;
                }
                transitionedInBatch++;
                expired++;
                remember(affected, evidence);
                countTransition(EvidenceStatus.EXPIRED);

                publisher.publishEvidenceExpired(evidence, now, REASON);
                publisher.publishAudit(AUDIT_ENTITY_TYPE, evidence.evidenceId(), evidence.merchantId(),
                        ACTION_EXPIRED,
                        Map.of("status", String.valueOf(evidence.status())),
                        Map.of("status", EvidenceStatus.EXPIRED.name(),
                                "expiresAt", String.valueOf(evidence.expiresAt()),
                                "reason", REASON),
                        null);
            }
            // A full batch that transitioned nothing means every row is being handled elsewhere;
            // looping again would spin.
            if (transitionedInBatch == 0 || due.size() < config.getBatchSize()) {
                break;
            }
        }
        return expired;
    }

    /**
     * Pass 2: ACTIVE artifacts entering the warning window become EXPIRING.
     *
     * <p>No canonical event is published: the contract defines {@code EvidenceExpired} but no
     * "EvidenceExpiring" type (section 3.1), and inventing one would break every consumer's
     * {@code EventType.fromWire}. The state change still reaches the outside world - through the
     * readiness recomputation it triggers, which is where an EXPIRING_SOON gap appears - and it is
     * recorded in the audit trail.
     */
    private int warningPass(Instant now, Instant windowEnd, ReadinessProperties.Sweep config,
                            Map<String, String> affected) {
        int marked = 0;
        for (int batch = 0; batch < Math.max(1, config.getMaxBatches()); batch++) {
            List<ExpiringEvidence> entering =
                    store.findEnteringWarningWindow(now, windowEnd, config.getBatchSize());
            if (entering.isEmpty()) {
                break;
            }
            int transitionedInBatch = 0;
            for (ExpiringEvidence evidence : entering) {
                if (!store.transition(evidence.evidenceId(), List.of(EvidenceStatus.ACTIVE),
                        EvidenceStatus.EXPIRING, now)) {
                    continue;
                }
                transitionedInBatch++;
                marked++;
                remember(affected, evidence);
                countTransition(EvidenceStatus.EXPIRING);

                publisher.publishAudit(AUDIT_ENTITY_TYPE, evidence.evidenceId(), evidence.merchantId(),
                        ACTION_EXPIRING,
                        Map.of("status", EvidenceStatus.ACTIVE.name()),
                        Map.of("status", EvidenceStatus.EXPIRING.name(),
                                "expiresAt", String.valueOf(evidence.expiresAt())),
                        null);
            }
            if (transitionedInBatch == 0 || entering.size() < config.getBatchSize()) {
                break;
            }
        }
        return marked;
    }

    /** Pass 3: every transaction the sweep touched gets a debounced recomputation. */
    private int queueRecomputations(ReadinessProperties.Sweep config, Map<String, String> affected,
                                    Instant at) {
        if (!config.isRecomputeAffected() || affected.isEmpty()) {
            return 0;
        }
        Set<String> queued = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : affected.entrySet()) {
            debouncer.submit(RecomputeRequest.fromSweep(entry.getKey(), entry.getValue(), null, at));
            queued.add(entry.getKey());
        }
        return queued.size();
    }

    /** Evidence with no transaction (merchant-level policy documents) has nothing to rescore. */
    private static void remember(Map<String, String> affected, ExpiringEvidence evidence) {
        if (evidence.transactionId() != null && !evidence.transactionId().isBlank()) {
            affected.putIfAbsent(evidence.transactionId(), evidence.merchantId());
        }
    }

    private void countTransition(EvidenceStatus status) {
        if (metrics != null) {
            metrics.expiryTransition(status.name());
        }
    }

    /**
     * What one sweep did.
     *
     * @param transactionsQueued distinct transactions handed to the debouncer, which is at most the
     *                           number of computations the sweep will cause
     */
    public record SweepResult(int expired, int markedExpiring, int transactionsQueued,
                              Instant startedAt, Instant finishedAt) {

        public Duration duration() {
            return startedAt == null || finishedAt == null
                    ? Duration.ZERO : Duration.between(startedAt, finishedAt);
        }

        public boolean changedNothing() {
            return expired == 0 && markedExpiring == 0;
        }
    }
}
