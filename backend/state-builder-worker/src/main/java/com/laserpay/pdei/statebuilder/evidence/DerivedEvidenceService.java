package com.laserpay.pdei.statebuilder.evidence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.core.evidence.CreateEvidenceCommand;
import com.laserpay.pdei.core.evidence.EvidenceService;
import com.laserpay.pdei.core.model.EvidenceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns a lifecycle fact into an evidence artifact.
 *
 * <p>The insight this class implements: <em>evidence already exists at the moment the fact
 * happens</em>. A capture confirmation, an order record, a carrier's delivery proof - these are not
 * things to go looking for when a dispute arrives 45 days later. They are things to capture, hash
 * and version the moment the event lands, which is what turns "assemble a case" from an
 * archaeological dig into a database read.
 *
 * <h2>The derivation is deterministic</h2>
 *
 * The artifact's bytes are the canonical JSON (sorted keys) of a document built <strong>only</strong>
 * from fields of the source event: its id, type, aggregate, transaction, {@code occurredAt} and
 * payload. No wall-clock reading, no random id, nothing that changes between runs.
 *
 * <p>That matters because {@code EvidenceService.createEvidence} deduplicates on
 * {@code (sha256, transactionId)}. Identical bytes mean a replayed or redelivered event returns the
 * <em>existing</em> artifact instead of creating a second one. Idempotency is a property of the
 * content here, not of a lock.
 *
 * <h2>What this class does not do</h2>
 *
 * It does not write the evidence tables, compute hashes, or publish {@code EvidenceAdded}.
 * {@code EvidenceService} does all three: it hashes the bytes it actually wrote to MinIO, inserts
 * the evidence and version rows, appends to the hash-chained audit log, and publishes
 * {@code EvidenceAdded} to {@code pdei.evidence.events.v1}. Routing evidence creation through one
 * component is what makes provenance impossible to bypass.
 */
public class DerivedEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(DerivedEvidenceService.class);

    /** Recorded as the actor on the audit entry for every derived artifact. */
    public static final String ACTOR = "state-builder-worker";

    /**
     * Quality score for a machine-observed fact. Merchant-entered facts score lower because they
     * are self-reported: the readiness engine weighs them accordingly.
     */
    private static final double MACHINE_QUALITY = 1.0d;
    private static final double SELF_REPORTED_QUALITY = 0.7d;

    private final EvidenceService evidenceService;

    /**
     * @param evidenceService may be {@code null} when the object store is unavailable; derivation
     *                        then degrades to a warning instead of failing the event, because
     *                        losing a projection write to an evidence-storage outage would be the
     *                        worse failure
     */
    public DerivedEvidenceService(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    /**
     * Derives an evidence artifact from a lifecycle event.
     *
     * @param transactionId    the transaction the artifact belongs to; required by the evidence model
     * @param relatedEntityId  the payment/order/shipment/refund the artifact documents
     * @param summary          one-line human description, surfaced in the AI context and the UI
     * @return the created (or already-existing) artifact, or {@code null} when derivation was
     *         skipped
     */
    public EvidenceView derive(CanonicalEvent event, EvidenceType type, String transactionId,
                               String relatedEntityId, String summary) {
        if (event == null || type == null) {
            return null;
        }
        if (transactionId == null || transactionId.isBlank()) {
            log.debug("skipping {} derivation for {}: no transaction to attach it to", type,
                    event.eventId());
            return null;
        }
        if (evidenceService == null) {
            log.warn("EvidenceService unavailable: {} not derived from {} {}. Projection state is "
                    + "unaffected; re-run the event once object storage is reachable.", type,
                    event.eventType(), event.eventId());
            return null;
        }

        byte[] content = documentFor(event, type, transactionId, relatedEntityId, summary);
        EvidenceSource source = sourceOf(event);

        CreateEvidenceCommand command = new CreateEvidenceCommand(
                event.merchantId(),
                transactionId,
                type,
                source,
                filenameFor(type, event),
                "application/json",
                content,
                summary,
                event.eventId(),
                event.correlationId(),
                relatedEntityId,
                event.occurredAt(),
                null,
                qualityFor(source),
                source != EvidenceSource.MERCHANT_PORTAL,
                ACTOR);

        EvidenceView view = evidenceService.createEvidence(command);
        log.info("derived {} {} for transaction {} from {} {}", type, view.evidenceId(),
                transactionId, event.eventType(), event.eventId());
        return view;
    }

    /**
     * The artifact's bytes: canonical JSON over event-derived fields only.
     *
     * <p>Deliberately excludes {@code observedAt} and any clock reading. Including them would make
     * the sha256 differ between the original delivery and a replay, defeating the content-based
     * deduplication in {@code EvidenceService}.
     */
    static byte[] documentFor(CanonicalEvent event, EvidenceType type, String transactionId,
                              String relatedEntityId, String summary) {
        ObjectNode document = Json.mapper().createObjectNode();
        document.put("evidenceKind", type.name());
        document.put("derivedFromEventId", event.eventId());
        document.put("derivedFromEventType", event.eventType().name());
        document.put("aggregateType", event.aggregateType().name());
        document.put("aggregateId", event.aggregateId());
        document.put("merchantId", event.merchantId());
        document.put("transactionId", transactionId);
        if (relatedEntityId != null) {
            document.put("relatedEntityId", relatedEntityId);
        }
        if (summary != null) {
            document.put("summary", summary);
        }
        document.put("occurredAt", event.occurredAt().toString());
        document.put("source", event.source().name());
        document.set("payload", event.payload().deepCopy());
        return Json.canonical(document).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Maps the event's provenance onto the evidence provenance vocabulary. The two enums are
     * deliberately distinct in the contract: {@code EventSource.INTERNAL} becomes
     * {@code EvidenceSource.INTERNAL_DERIVED}, which is how a reader can tell a fact PDEI inferred
     * from a fact a source system asserted.
     */
    static EvidenceSource sourceOf(CanonicalEvent event) {
        return switch (event.source()) {
            case PSP_ADAPTER -> EvidenceSource.PSP_ADAPTER;
            case ORDER_SYSTEM -> EvidenceSource.ORDER_SYSTEM;
            case LOGISTICS -> EvidenceSource.LOGISTICS;
            case CRM -> EvidenceSource.CRM;
            case MERCHANT_PORTAL -> EvidenceSource.MERCHANT_PORTAL;
            case SIMULATOR -> EvidenceSource.SIMULATOR;
            case INTERNAL -> EvidenceSource.INTERNAL_DERIVED;
        };
    }

    private static double qualityFor(EvidenceSource source) {
        return source == EvidenceSource.MERCHANT_PORTAL ? SELF_REPORTED_QUALITY : MACHINE_QUALITY;
    }

    private static String filenameFor(EvidenceType type, CanonicalEvent event) {
        return type.name().toLowerCase(Locale.ROOT) + "-" + event.aggregateId() + ".json";
    }
}
