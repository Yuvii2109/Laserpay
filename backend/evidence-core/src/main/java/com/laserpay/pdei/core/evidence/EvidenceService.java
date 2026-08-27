package com.laserpay.pdei.core.evidence;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.audit.AuditCommand;
import com.laserpay.pdei.core.audit.AuditRecorder;
import com.laserpay.pdei.core.model.EvidenceEdge;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.core.spi.EvidenceRelationship;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.EvidenceVersionRecord;
import com.laserpay.pdei.core.storage.Buckets;
import com.laserpay.pdei.core.storage.ObjectStore;
import com.laserpay.pdei.core.storage.StoredObject;
import com.laserpay.pdei.core.util.CoreErrors;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lifecycle of evidence artifacts: create, version, expire, invalidate, link.
 *
 * <p>Invariants this service enforces:</p>
 * <ul>
 *   <li><b>Content is hashed here, never trusted.</b> The sha256 recorded in Postgres is computed
 *       from the bytes that were actually written to MinIO.</li>
 *   <li><b>Nothing is ever overwritten.</b> A correction is a new evidence row with a new id, a new
 *       object key and {@code version = parent.version + 1}; the parent moves to SUPERSEDED.</li>
 *   <li><b>Creation is idempotent.</b> The same bytes on the same transaction return the existing
 *       artifact instead of duplicating it, so replayed or duplicated events are harmless.</li>
 *   <li><b>Every transition is audited and published.</b> Postgres, MinIO, the audit chain and the
 *       evidence topic always tell the same story.</li>
 * </ul>
 */
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);
    private static final String ENTITY_TYPE = "EVIDENCE";
    private static final String METRIC_EVIDENCE_TOTAL = "pdei_evidence_total";

    private final EvidenceRepositoryPort repository;
    private final ObjectStore objectStore;
    private final EventPublisherPort publisher;
    private final AuditRecorder audit;
    private final PolicyEngine policyEngine;
    private final Clocks clock;
    private final MeterRegistry meterRegistry;
    private final Duration presignTtl;

    public EvidenceService(EvidenceRepositoryPort repository, ObjectStore objectStore,
                           EventPublisherPort publisher, AuditRecorder audit, PolicyEngine policyEngine,
                           Clocks clock, MeterRegistry meterRegistry, Duration presignTtl) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.publisher = publisher;
        this.audit = audit;
        this.policyEngine = policyEngine;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.presignTtl = presignTtl == null ? Duration.ofMinutes(15) : presignTtl;
    }

    // --- read -----------------------------------------------------------------------------------

    public Optional<EvidenceView> find(String evidenceId) {
        return repository.findById(evidenceId);
    }

    public EvidenceView require(String evidenceId) {
        return repository.findById(evidenceId)
                .orElseThrow(() -> CoreErrors.notFound(ENTITY_TYPE, evidenceId));
    }

    public List<EvidenceView> findForTransaction(String transactionId) {
        return repository.findByTransactionId(transactionId);
    }

    /** Usable evidence only - what a representment package may draw from. */
    public List<EvidenceView> findUsableForTransaction(String transactionId) {
        return repository.findByTransactionIdAndStatusIn(transactionId, EvidenceView.USABLE);
    }

    /** Time-limited download URL for {@code GET /api/v1/evidence/{evidenceId}/download}. */
    public String downloadUrl(String evidenceId) {
        EvidenceView view = require(evidenceId);
        return objectStore.presignedGet(Buckets.EVIDENCE, view.objectKey(), presignTtl);
    }

    // --- create ---------------------------------------------------------------------------------

    /**
     * Register a new artifact: hash the bytes, write them to MinIO under the contract key layout,
     * insert the evidence and evidence_version rows, audit, then publish {@code EvidenceAdded}.
     *
     * <p>Ordering matters. The object is written before the row so a crash leaves an orphan object
     * (harmless, reclaimable) rather than a row pointing at nothing (an unfixable integrity failure).
     * The event is published last, after the state it describes is durable.</p>
     */
    public EvidenceView createEvidence(CreateEvidenceCommand command) {
        CoreErrors.requireValue(command, "command");
        CoreErrors.requireText(command.merchantId(), "merchantId");
        CoreErrors.requireText(command.transactionId(), "transactionId");
        CoreErrors.requireValue(command.type(), "type");
        CoreErrors.requireValue(command.source(), "source");
        CoreErrors.requireValue(command.content(), "content");
        if (command.content().length == 0) {
            throw CoreErrors.invalid("evidence content is empty");
        }

        Instant now = clock.now();
        String sha256 = Hashes.sha256(command.content());

        Optional<EvidenceView> existing =
                repository.findByShaAndTransactionId(sha256, command.transactionId());
        if (existing.isPresent()) {
            log.debug("evidence with sha256={} already present on transactionId={} as {}", sha256,
                    command.transactionId(), existing.get().evidenceId());
            return existing.get();
        }

        String evidenceId = Ids.evidence();
        int version = 1;
        String filename = Buckets.safeFilename(command.filename());
        String objectKey = Buckets.evidenceKey(command.merchantId(), command.transactionId(),
                command.type(), evidenceId, version, filename);

        StoredObject stored = objectStore.put(Buckets.EVIDENCE, objectKey, command.content(),
                command.contentType(), metadata(evidenceId, version, command.sourceEventId()));

        Instant expiresAt = command.expiresAt() != null
                ? command.expiresAt()
                : policyEngine.expiryFor(policyEngine.applicablePolicy(command.merchantId(), null),
                        command.type(), now);

        EvidenceView view = new EvidenceView(
                evidenceId,
                command.merchantId(),
                command.transactionId(),
                command.type(),
                EvidenceStatus.ACTIVE,
                command.source(),
                objectKey,
                stored.sha256(),
                version,
                filename,
                stored.contentType(),
                stored.sizeBytes(),
                command.summary(),
                command.sourceEventId(),
                null,
                command.relatedEntityId(),
                command.qualityScore(),
                command.provenanceVerified(),
                now,
                command.observedAt() == null ? now : command.observedAt(),
                expiresAt);

        repository.insert(view);
        repository.insertVersion(new EvidenceVersionRecord(evidenceId + "-V" + version, evidenceId, version,
                objectKey, stored.sha256(), stored.sizeBytes(), stored.contentType(), filename,
                command.sourceEventId(), command.actor(), now));

        audit.record(AuditCommand.of(ENTITY_TYPE, evidenceId, command.merchantId(), "EVIDENCE_CREATED",
                        command.actor(), actorTypeFor(command))
                .withAfter(view)
                .withCorrelationId(command.correlationId()));

        publish(EventType.EvidenceAdded, view, command.correlationId(), command.sourceEventId());
        count(view);
        log.info("created evidence {} type={} transactionId={} sha256={}", evidenceId, command.type(),
                command.transactionId(), stored.sha256());
        return view;
    }

    // --- version --------------------------------------------------------------------------------

    /**
     * Supersede an artifact with a corrected one. The parent row is never rewritten: it moves to
     * SUPERSEDED, the new row carries {@code parentEvidenceId} and {@code version + 1}, and a
     * SUPERSEDES relationship records the link for lineage walks.
     */
    public EvidenceView newVersion(NewVersionCommand command) {
        CoreErrors.requireValue(command, "command");
        EvidenceView parent = require(CoreErrors.requireText(command.parentEvidenceId(), "parentEvidenceId"));
        CoreErrors.requireValue(command.content(), "content");
        if (command.content().length == 0) {
            throw CoreErrors.invalid("evidence content is empty");
        }
        if (parent.status() == EvidenceStatus.SUPERSEDED) {
            throw CoreErrors.conflict("evidence " + parent.evidenceId()
                    + " is already superseded; version the current head instead");
        }

        Instant now = clock.now();
        String sha256 = Hashes.sha256(command.content());
        if (sha256.equals(parent.sha256())) {
            log.debug("new version of {} has identical content; returning the existing version",
                    parent.evidenceId());
            return parent;
        }

        String evidenceId = Ids.evidence();
        int version = parent.version() + 1;
        String filename = Buckets.safeFilename(
                command.filename() == null ? parent.filename() : command.filename());
        String objectKey = Buckets.evidenceKey(parent.merchantId(), parent.transactionId(), parent.type(),
                evidenceId, version, filename);

        StoredObject stored = objectStore.put(Buckets.EVIDENCE, objectKey, command.content(),
                command.contentType() == null ? parent.contentType() : command.contentType(),
                metadata(evidenceId, version, command.sourceEventId()));

        EvidenceView view = new EvidenceView(
                evidenceId,
                parent.merchantId(),
                parent.transactionId(),
                parent.type(),
                EvidenceStatus.ACTIVE,
                parent.source(),
                objectKey,
                stored.sha256(),
                version,
                filename,
                stored.contentType(),
                stored.sizeBytes(),
                command.summary() == null ? parent.summary() : command.summary(),
                command.sourceEventId(),
                parent.evidenceId(),
                parent.relatedEntityId(),
                command.qualityScore(),
                command.provenanceVerified(),
                now,
                command.observedAt() == null ? now : command.observedAt(),
                command.expiresAt() == null
                        ? policyEngine.expiryFor(
                                policyEngine.applicablePolicy(parent.merchantId(), null), parent.type(), now)
                        : command.expiresAt());

        repository.insert(view);
        repository.insertVersion(new EvidenceVersionRecord(evidenceId + "-V" + version, evidenceId, version,
                objectKey, stored.sha256(), stored.sizeBytes(), stored.contentType(), filename,
                command.sourceEventId(), command.actor(), now));
        repository.updateStatus(parent.evidenceId(), EvidenceStatus.SUPERSEDED, now,
                command.reason() == null ? "superseded by " + evidenceId : command.reason());
        repository.insertRelationship(new EvidenceRelationship(
                evidenceId + ">" + parent.evidenceId(), evidenceId, parent.evidenceId(),
                EvidenceEdge.SUPERSEDES, command.reason(), now));

        audit.record(AuditCommand.of(ENTITY_TYPE, evidenceId, parent.merchantId(), "EVIDENCE_VERSIONED",
                        command.actor(), ActorType.SYSTEM)
                .withBefore(parent)
                .withAfter(view)
                .withCorrelationId(command.correlationId()));

        publish(EventType.EvidenceAdded, view, command.correlationId(), command.sourceEventId());
        count(view);
        log.info("evidence {} superseded by {} (v{})", parent.evidenceId(), evidenceId, version);
        return view;
    }

    // --- lifecycle transitions ------------------------------------------------------------------

    /** Move an artifact to EXPIRED and publish {@code EvidenceExpired}. */
    public EvidenceView expire(String evidenceId, String reason, String actor) {
        EvidenceView before = require(evidenceId);
        if (before.status() == EvidenceStatus.EXPIRED) {
            return before;
        }
        Instant now = clock.now();
        repository.updateStatus(evidenceId, EvidenceStatus.EXPIRED, now, reason);
        EvidenceView after = statusOnly(before, EvidenceStatus.EXPIRED);
        audit.record(AuditCommand.of(ENTITY_TYPE, evidenceId, before.merchantId(), "EVIDENCE_EXPIRED",
                        actor, ActorType.SYSTEM)
                .withBefore(before)
                .withAfter(after));
        publish(EventType.EvidenceExpired, after, null, null);
        count(after);
        return after;
    }

    /** Flag an artifact as EXPIRING (inside the policy warning window) without publishing an event. */
    public EvidenceView markExpiring(String evidenceId) {
        EvidenceView before = require(evidenceId);
        if (before.status() != EvidenceStatus.ACTIVE) {
            return before;
        }
        repository.updateStatus(evidenceId, EvidenceStatus.EXPIRING, clock.now(), "expiry window entered");
        return statusOnly(before, EvidenceStatus.EXPIRING);
    }

    /**
     * Move an artifact to INVALIDATED and publish {@code EvidenceInvalidated}. Used by integrity
     * verification on a hash mismatch and by an operator rejecting a document.
     */
    public EvidenceView invalidate(String evidenceId, String reason, String actor) {
        EvidenceView before = require(evidenceId);
        if (before.status() == EvidenceStatus.INVALIDATED) {
            return before;
        }
        Instant now = clock.now();
        repository.updateStatus(evidenceId, EvidenceStatus.INVALIDATED, now, reason);
        EvidenceView after = statusOnly(before, EvidenceStatus.INVALIDATED);
        audit.record(AuditCommand.of(ENTITY_TYPE, evidenceId, before.merchantId(), "EVIDENCE_INVALIDATED",
                        actor, ActorType.SYSTEM)
                .withBefore(before)
                .withAfter(after));
        publish(EventType.EvidenceInvalidated, after, null, reason);
        count(after);
        log.warn("evidence {} invalidated: {}", evidenceId, reason);
        return after;
    }

    /** Record a typed relationship between two artifacts (see {@link EvidenceEdge} constants). */
    public void link(String fromEvidenceId, String toEvidenceId, String relation, String detail, String actor) {
        EvidenceView from = require(fromEvidenceId);
        require(toEvidenceId);
        if (fromEvidenceId.equals(toEvidenceId)) {
            throw CoreErrors.invalid("evidence cannot be linked to itself: " + fromEvidenceId);
        }
        Instant now = clock.now();
        repository.insertRelationship(new EvidenceRelationship(fromEvidenceId + ">" + toEvidenceId,
                fromEvidenceId, toEvidenceId,
                relation == null ? EvidenceEdge.RELATES_TO : relation, detail, now));
        audit.record(AuditCommand.of(ENTITY_TYPE, fromEvidenceId, from.merchantId(), "EVIDENCE_LINKED",
                        actor, ActorType.SYSTEM)
                .withAfter(Map.of("to", toEvidenceId, "relation", String.valueOf(relation))));
    }

    /**
     * Nightly sweep: move artifacts into EXPIRING as they enter the warning window and into EXPIRED
     * once they pass it. Idempotent, so it can run as often as needed.
     */
    public int sweepExpiry(int warningDays, int limit) {
        Instant now = clock.now();
        int changed = 0;
        for (EvidenceView view : repository.findExpiringBetween(Instant.EPOCH, now, limit)) {
            if (view.status() != EvidenceStatus.EXPIRED && view.status() != EvidenceStatus.INVALIDATED
                    && view.status() != EvidenceStatus.SUPERSEDED) {
                expire(view.evidenceId(), "expiry sweep", "SYSTEM");
                changed++;
            }
        }
        Instant windowEnd = now.plus(Duration.ofDays(Math.max(1, warningDays)));
        for (EvidenceView view : repository.findExpiringBetween(now, windowEnd, limit)) {
            if (view.status() == EvidenceStatus.ACTIVE) {
                markExpiring(view.evidenceId());
                changed++;
            }
        }
        return changed;
    }

    // --- helpers --------------------------------------------------------------------------------

    /** User metadata stamped onto every object (platform contract 11). */
    private static Map<String, String> metadata(String evidenceId, int version, String sourceEventId) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(Buckets.META_EVIDENCE_ID, evidenceId);
        metadata.put(Buckets.META_VERSION, String.valueOf(version));
        if (sourceEventId != null && !sourceEventId.isBlank()) {
            metadata.put(Buckets.META_SOURCE_EVENT_ID, sourceEventId);
        }
        return metadata;
    }

    private static EvidenceView statusOnly(EvidenceView view, EvidenceStatus status) {
        return new EvidenceView(view.evidenceId(), view.merchantId(), view.transactionId(), view.type(),
                status, view.source(), view.objectKey(), view.sha256(), view.version(), view.filename(),
                view.contentType(), view.sizeBytes(), view.summary(), view.sourceEventId(),
                view.parentEvidenceId(), view.relatedEntityId(), view.qualityScore(),
                view.provenanceVerified(), view.createdAt(), view.observedAt(), view.expiresAt());
    }

    private static ActorType actorTypeFor(CreateEvidenceCommand command) {
        return switch (command.source()) {
            case MERCHANT_PORTAL, DOCUMENT_UPLOAD -> ActorType.MERCHANT_USER;
            case SIMULATOR -> ActorType.SIMULATOR;
            default -> ActorType.SYSTEM;
        };
    }

    private void publish(EventType eventType, EvidenceView view, String correlationId, String causationId) {
        CanonicalEvent event = new CanonicalEvent(
                Ids.eventId(),
                eventType,
                1,
                AggregateType.EVIDENCE,
                view.evidenceId(),
                view.merchantId(),
                correlationId == null ? Ids.eventId() : correlationId,
                causationId,
                clock.now(),
                clock.now(),
                EventSource.INTERNAL,
                eventType.name() + ":" + view.evidenceId() + ":v" + view.version() + ":" + view.status(),
                Json.tree(view));
        publisher.publish(Topics.EVIDENCE_EVENTS, event);
    }

    private void count(EvidenceView view) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(METRIC_EVIDENCE_TOTAL,
                    "type", String.valueOf(view.type()),
                    "status", String.valueOf(view.status())).increment();
        } catch (RuntimeException e) {
            // never let metrics break evidence handling
        }
    }
}
