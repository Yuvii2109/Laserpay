package com.laserpay.pdei.readiness.publish;

import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.readiness.persistence.EvidenceExpiryStore.ExpiringEvidence;
import com.laserpay.pdei.readiness.recompute.RecomputeTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything this worker emits, in one place.
 *
 * <p>Three streams leave the readiness worker:
 * <ul>
 *   <li>{@code ReadinessRecomputed} and {@code ReadinessGapDetected} on
 *       {@code pdei.readiness.events.v1} (PLATFORM-CONTRACT section 4), consumed by
 *       api-gateway-service for the live control tower and by audit-service for the trail;</li>
 *   <li>{@code EvidenceExpired} on {@code pdei.evidence.events.v1}, emitted by the expiry sweep;</li>
 *   <li>{@code AuditEvent}s on {@code pdei.audit.events.v1} for every state change the worker
 *       causes - audit-service owns the hash chain, this service only reports.</li>
 * </ul>
 *
 * <p>The partition key is always {@code merchantId + ":" + aggregateId}, applied by
 * {@code KafkaEventPublisher} from {@link CanonicalEvent#partitionKey()}. For readiness events the
 * aggregate is the transaction, so a merchant's transaction keeps a single ordered stream across
 * evidence changes, recomputations and expiries.
 *
 * <p>Publication never throws. The score is already computed and committed by the time these run;
 * failing the unit of work because a broker hiccuped would discard correct financial state to
 * protect a notification.
 */
public class ReadinessEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReadinessEventPublisher.class);

    /** {@code actor} recorded on audit entries this worker raises. */
    public static final String ACTOR = "readiness-worker";

    private final EventPublisherPort publisher;
    private final Clocks clock;

    public ReadinessEventPublisher(EventPublisherPort publisher, Clocks clock) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Announce a new readiness snapshot.
     *
     * <p>The payload is the score summary rather than the whole snapshot: consumers that need the
     * requirement matrix read {@code GET /transactions/{id}/readiness}, and a Kafka topic is a poor
     * place to fan out a document that is already cached and persisted.
     */
    public CanonicalEvent publishRecomputed(ReadinessSnapshot snapshot, RecomputeTrigger trigger,
                                            String causationEventId, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", snapshot.snapshotId());
        payload.put("transactionId", snapshot.transactionId());
        payload.put("score", snapshot.score());
        payload.put("band", snapshot.band() == null ? null : snapshot.band().name());
        payload.put("baseScore", snapshot.baseScore());
        payload.put("penaltyPoints", snapshot.penaltyPoints());
        payload.put("reasonCode", snapshot.reasonCode() == null ? null : snapshot.reasonCode().name());
        payload.put("gapCount", snapshot.gaps().size());
        payload.put("contradictionCount", snapshot.contradictions().size());
        payload.put("allMandatorySatisfied", snapshot.allMandatorySatisfied());
        payload.put("policyVersionId", snapshot.policyVersionId());
        payload.put("trigger", trigger == null ? null : trigger.name());
        payload.put("computedAt", snapshot.computedAt() == null ? null : snapshot.computedAt().toString());

        CanonicalEvent event = readinessEvent(EventType.ReadinessRecomputed, snapshot.transactionId(),
                snapshot.merchantId(), correlationId, causationEventId, snapshot.computedAt(), payload,
                // A recomputation of the same transaction at the same instant for the same reason
                // code is the same fact, however many times it is delivered.
                idempotencyKey("readiness-recomputed", snapshot.transactionId(),
                        String.valueOf(snapshot.reasonCode()), String.valueOf(snapshot.computedAt())));

        publisher.publish(Topics.READINESS_EVENTS, event);
        return event;
    }

    /**
     * Announce the blocking gaps of a snapshot.
     *
     * <p>One event per snapshot carrying all qualifying gaps, not one per gap: a transaction with
     * eight missing documents is one problem for a merchant, not eight notifications.
     *
     * @return the event published, or null when nothing met the severity floor
     */
    public CanonicalEvent publishGapDetected(ReadinessSnapshot snapshot, List<ReadinessGap> gaps,
                                             String causationEventId, String correlationId) {
        if (gaps == null || gaps.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> serialisedGaps = gaps.stream().map(gap -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("gapId", gap.gapId());
            node.put("type", gap.type() == null ? null : gap.type().name());
            node.put("evidenceType", gap.evidenceType() == null ? null : gap.evidenceType().name());
            node.put("severity", gap.severity() == null ? null : gap.severity().name());
            node.put("evidenceId", gap.evidenceId());
            node.put("detail", gap.detail());
            node.put("expiresAt", gap.expiresAt() == null ? null : gap.expiresAt().toString());
            return node;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", snapshot.snapshotId());
        payload.put("transactionId", snapshot.transactionId());
        payload.put("score", snapshot.score());
        payload.put("band", snapshot.band() == null ? null : snapshot.band().name());
        payload.put("worstSeverity", worstSeverity(gaps).name());
        payload.put("gaps", serialisedGaps);

        CanonicalEvent event = readinessEvent(EventType.ReadinessGapDetected, snapshot.transactionId(),
                snapshot.merchantId(), correlationId, causationEventId, snapshot.computedAt(), payload,
                idempotencyKey("readiness-gaps", snapshot.transactionId(),
                        // The gap set itself is the identity: the same open gaps re-detected on a
                        // later recomputation must not spam a fresh notification.
                        Hashes.sha256Hex(String.join(",",
                                gaps.stream().map(ReadinessGap::gapId).sorted().toList()))));

        publisher.publish(Topics.READINESS_EVENTS, event);
        return event;
    }

    /**
     * Announce that an artifact has passed its retention window.
     *
     * <p>Published on {@code pdei.evidence.events.v1} because it is an EVIDENCE event by type
     * (contract section 3.1) - which also means this worker's own evidence consumer will see it and
     * schedule the follow-up recomputation, exactly like any other producer's evidence event.
     */
    public CanonicalEvent publishEvidenceExpired(ExpiringEvidence evidence, Instant at, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("evidenceId", evidence.evidenceId());
        payload.put("transactionId", evidence.transactionId());
        payload.put("type", evidence.type() == null ? null : evidence.type().name());
        payload.put("previousStatus", evidence.status() == null ? null : evidence.status().name());
        payload.put("expiresAt", evidence.expiresAt() == null ? null : evidence.expiresAt().toString());
        payload.put("reason", reason);

        CanonicalEvent event = CanonicalEvent.builder()
                .eventId(Ids.eventId())
                .eventType(EventType.EvidenceExpired)
                .schemaVersion(CanonicalEvent.CURRENT_SCHEMA_VERSION)
                .aggregateType(AggregateType.EVIDENCE)
                .aggregateId(evidence.evidenceId())
                .merchantId(evidence.merchantId())
                .causationId(evidence.sourceEventId())
                .occurredAt(at)
                .observedAt(clock.now())
                .source(EventSource.INTERNAL)
                // Stable across sweep runs: expiring the same artifact twice is one fact.
                .idempotencyKey(idempotencyKey("evidence-expired", evidence.evidenceId(),
                        String.valueOf(evidence.expiresAt())))
                .payload(Json.tree(payload))
                .build();

        publisher.publish(Topics.EVIDENCE_EVENTS, event);
        return event;
    }

    /**
     * Report a state change to the audit trail.
     *
     * <p>The worker deliberately does not write {@code audit_events} itself. audit-service owns the
     * hash chain; two writers appending to one per-merchant chain would fork it. Publishing the
     * intent keeps a single authority over the chain (contract section 4, non-negotiable rule 8).
     *
     * <p>{@code previousHash} and {@code hash} are left for audit-service to seal, so this record
     * carries a self-hash only - it is a report, not a chain link.
     */
    public void publishAudit(String entityType, String entityId, String merchantId, String action,
                             Object before, Object after, String correlationId) {
        try {
            AuditEvent event = new AuditEvent(
                    Ids.audit(),
                    entityType,
                    entityId,
                    merchantId,
                    action,
                    ACTOR,
                    ActorType.SYSTEM,
                    clock.now(),
                    correlationId,
                    before == null ? null : Json.tree(before),
                    after == null ? null : Json.tree(after),
                    Hashes.GENESIS_HASH,
                    null).withHash();
            publisher.publishAudit(event);
        } catch (RuntimeException e) {
            log.warn("could not publish audit entry action={} entityId={}: {}", action, entityId, e.toString());
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private CanonicalEvent readinessEvent(EventType type, String transactionId, String merchantId,
                                          String correlationId, String causationId, Instant occurredAt,
                                          Map<String, Object> payload, String idempotencyKey) {
        Instant now = clock.now();
        return CanonicalEvent.builder()
                .eventId(Ids.eventId())
                .eventType(type)
                .schemaVersion(CanonicalEvent.CURRENT_SCHEMA_VERSION)
                .aggregateType(AggregateType.TRANSACTION)
                .aggregateId(transactionId)
                .merchantId(merchantId)
                .correlationId(correlationId)
                .causationId(causationId)
                .occurredAt(occurredAt == null ? now : occurredAt)
                .observedAt(now)
                .source(EventSource.INTERNAL)
                .idempotencyKey(idempotencyKey)
                .payload(Json.tree(payload))
                .build();
    }

    private static String idempotencyKey(String kind, String... parts) {
        return kind + ":" + String.join(":", parts);
    }

    private static GapSeverity worstSeverity(List<ReadinessGap> gaps) {
        GapSeverity worst = GapSeverity.LOW;
        for (ReadinessGap gap : gaps) {
            if (gap.severity() != null && gap.severity().ordinal() > worst.ordinal()) {
                worst = gap.severity();
            }
        }
        return worst;
    }
}
