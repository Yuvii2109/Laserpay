package com.laserpay.pdei.simulator.chaos;

import com.laserpay.pdei.common.domain.ChaosType;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.core.storage.Buckets;
import com.laserpay.pdei.core.storage.ObjectStore;
import com.laserpay.pdei.persistence.entity.ChaosInjectionEntity;
import com.laserpay.pdei.persistence.entity.EvidenceEntity;
import com.laserpay.pdei.persistence.repository.ChaosInjectionRepository;
import com.laserpay.pdei.persistence.repository.EvidenceRepository;
import com.laserpay.pdei.simulator.config.SimulatorProperties;
import com.laserpay.pdei.simulator.emit.EmissionControl;
import com.laserpay.pdei.simulator.emit.EventEmitter;
import com.laserpay.pdei.simulator.emit.SimulationRunner;
import com.laserpay.pdei.simulator.replay.ReplayRequest;
import com.laserpay.pdei.simulator.replay.ReplayResult;
import com.laserpay.pdei.simulator.replay.ReplayService;
import com.laserpay.pdei.simulator.world.SimEvent;
import com.laserpay.pdei.simulator.world.SourceVocabulary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;


import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Injects every {@link ChaosType} in platform contract section 6, records what it did, and
 * announces it.
 *
 * <h2>The point</h2>
 * A distributed system's resilience claims are unfalsifiable until something breaks it on
 * purpose. Each injection here targets one specific claim:
 *
 * <pre>
 * DUPLICATE_EVENT        consumers dedupe on eventId                     (rule 9)
 * DELAYED_EVENT          late arrival converges to the same state        (rule 10)
 * OUT_OF_ORDER_EVENT     ordering is not assumed within a partition      (rule 10)
 * DROP_EVENT             a missing artifact becomes a detected gap
 * DELETE_EVIDENCE        integrity verification catches a vanished object
 * CORRUPT_EVIDENCE_HASH  a tampered artifact fails its sha256 check      (rule 8)
 * EXPIRE_EVIDENCE        expiry moves readiness, and it is recomputed
 * CONFLICTING_EVIDENCE   contradictions are found, not averaged over
 * KILL_WORKER            Temporal recovers the workflow
 * RESTART_CONSUMER       a rebalance redelivers, and that is survivable
 * REPLAY_EVENTS          history can be re-consumed without damage      (rule 11)
 * INJECT_DISPUTE         the case pipeline starts from a cold dispute
 * SLOW_CONSUMER          lag is visible and bounded, not silent
 * </pre>
 *
 * <h2>Everything is recorded</h2>
 * Every injection writes a {@code chaos_injections} row before it acts and updates it afterwards,
 * so a failed injection is as visible as a successful one, and a demo can show precisely which
 * failure was injected and when. {@link ChaosNotifier} then emits the {@code CHAOS_INJECTED}
 * notification the console renders.
 *
 * <h2>Stream chaos needs a running stream</h2>
 * DUPLICATE / DELAYED / OUT_OF_ORDER / DROP set budgets on a live {@link EmissionControl}. When
 * no run is in flight, the duplicate case degrades to re-publishing retained traffic and the
 * others fail honestly rather than silently doing nothing.
 */
@Service
public class ChaosEngine {

    private static final Logger log = LoggerFactory.getLogger(ChaosEngine.class);

    private static final String TARGET_RUN_ID = "runId";
    private static final String TARGET_EVIDENCE_ID = "evidenceId";
    private static final String TARGET_TRANSACTION_ID = "transactionId";
    private static final String TARGET_MERCHANT_ID = "merchantId";
    private static final String TARGET_SERVICE = "service";
    private static final String TARGET_TOPIC = "topic";
    private static final String TARGET_FROM_OFFSET = "fromOffset";
    private static final String TARGET_FROM_TIMESTAMP = "fromTimestamp";
    private static final String TARGET_REASON_CODE = "reasonCode";

    private final ChaosInjectionRepository injections;
    private final EvidenceRepository evidenceRepository;
    private final ObjectProvider<ObjectStore> objectStores;
    private final ObjectProvider<EventPublisherPort> publishers;
    private final SimulationRunner runner;
    private final EventEmitter emitter;
    private final ReplayService replayService;
    private final WorkerControl workerControl;
    private final ChaosNotifier notifier;
    private final SimulatorProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clocks clock;

    public ChaosEngine(ChaosInjectionRepository injections,
                       EvidenceRepository evidenceRepository,
                       ObjectProvider<ObjectStore> objectStores,
                       ObjectProvider<EventPublisherPort> publishers,
                       SimulationRunner runner,
                       EventEmitter emitter,
                       ReplayService replayService,
                       WorkerControl workerControl,
                       ChaosNotifier notifier,
                       SimulatorProperties properties,
                       MeterRegistry meterRegistry,
                       Clocks clock) {
        this.injections = injections;
        this.evidenceRepository = evidenceRepository;
        this.objectStores = objectStores;
        this.publishers = publishers;
        this.runner = runner;
        this.emitter = emitter;
        this.replayService = replayService;
        this.workerControl = workerControl;
        this.notifier = notifier;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Applies one injection.
     *
     * <p>The {@code chaos_injections} row is written first, in REQUESTED, so a crash mid-injection
     * still leaves evidence that something was attempted. That is the difference between chaos
     * engineering and an unexplained outage.
     */
    public ChaosResult inject(ChaosRequest request) {
        if (request == null || request.type() == null) {
            throw new ValidationException("chaos type is required");
        }
        ChaosInjectionEntity record = persistRequested(request);
        ChaosResult result;
        try {
            result = dispatch(request, record.getId());
        } catch (RuntimeException e) {
            log.error("chaos injection {} ({}) failed", record.getId(), request.type(), e);
            result = ChaosResult.failed(record.getId(), request.type(),
                    "injection failed", e.toString(), clock.now());
        }
        persistOutcome(record.getId(), result);
        meterRegistry.counter(MetricNames.CHAOS_INJECTIONS_TOTAL,
                MetricNames.Tag.TYPE, request.type().name(),
                MetricNames.Tag.STATUS, result.status()).increment();
        notifier.notifyInjected(request, result);
        return result;
    }

    // =======================================================================================
    // Dispatch
    // =======================================================================================

    private ChaosResult dispatch(ChaosRequest request, String injectionId) {
        return switch (request.type()) {
            case DUPLICATE_EVENT -> duplicateEvent(request, injectionId);
            case DELAYED_EVENT -> delayedEvent(request, injectionId);
            case OUT_OF_ORDER_EVENT -> outOfOrderEvent(request, injectionId);
            case DROP_EVENT -> dropEvent(request, injectionId);
            case DELETE_EVIDENCE -> deleteEvidence(request, injectionId);
            case CORRUPT_EVIDENCE_HASH -> corruptEvidenceHash(request, injectionId);
            case EXPIRE_EVIDENCE -> expireEvidence(request, injectionId);
            case CONFLICTING_EVIDENCE -> conflictingEvidence(request, injectionId);
            case KILL_WORKER -> killWorker(request, injectionId);
            case RESTART_CONSUMER -> restartConsumer(request, injectionId);
            case SLOW_CONSUMER -> slowConsumer(request, injectionId);
            case REPLAY_EVENTS -> replayEvents(request, injectionId);
            case INJECT_DISPUTE -> injectDispute(request, injectionId);
        };
    }

    // ---------------------------------------------------------------------------------------
    // Event-stream chaos
    // ---------------------------------------------------------------------------------------

    /**
     * Re-delivers events byte-for-byte.
     *
     * <p>Against a live run this sets a budget the emitter consumes. With no run in flight it
     * re-publishes retained traffic directly, which exercises the same consumer-side path - the
     * duplicate carries the same {@code rawEventId} and {@code idempotencyKey}, so a consumer
     * that dedupes correctly is unmoved by either.
     */
    private ChaosResult duplicateEvent(ChaosRequest request, String injectionId) {
        int count = boundedCount(request);
        Optional<EmissionControl> control = controlFor(request);
        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put("count", count);

        if (control.isPresent()) {
            control.get().addDuplicateBudget(count);
            detail.put("runId", control.get().runId());
            detail.put("applied", "budget set on the live emission");
            return ChaosResult.applied(injectionId, ChaosType.DUPLICATE_EVENT,
                    ChaosResult.MODE_IN_PROCESS,
                    "next " + count + " events of run " + control.get().runId()
                            + " will be published twice", detail, clock.now());
        }

        List<SimEvent> traffic = retainedTraffic(request, count);
        if (traffic.isEmpty()) {
            return ChaosResult.failed(injectionId, ChaosType.DUPLICATE_EVENT,
                    "no running emission and no retained traffic to duplicate",
                    "start a run first, or pass a runId whose stream is still retained", clock.now());
        }
        long republished = traffic.stream()
                .filter(event -> emitter.publish(event.envelope(), event.aggregateId()))
                .count();
        detail.put("republished", republished);
        detail.put("applied", "retained traffic re-published directly");
        return ChaosResult.applied(injectionId, ChaosType.DUPLICATE_EVENT,
                ChaosResult.MODE_IN_PROCESS,
                "re-published " + republished + " retained events verbatim", detail, clock.now());
    }

    private ChaosResult delayedEvent(ChaosRequest request, String injectionId) {
        int count = boundedCount(request);
        long delayMillis = boundedDelay(request, 2_000L);
        EmissionControl control = requireControl(request, ChaosType.DELAYED_EVENT);
        control.addDelay(count, delayMillis);

        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put("runId", control.runId());
        detail.put("count", count);
        detail.put("delayMs", delayMillis);
        return ChaosResult.applied(injectionId, ChaosType.DELAYED_EVENT, ChaosResult.MODE_IN_PROCESS,
                "next " + count + " events delayed by " + delayMillis + " ms", detail, clock.now());
    }

    private ChaosResult outOfOrderEvent(ChaosRequest request, String injectionId) {
        int count = boundedCount(request);
        EmissionControl control = requireControl(request, ChaosType.OUT_OF_ORDER_EVENT);
        control.addOutOfOrderBudget(count);

        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put("runId", control.runId());
        detail.put("count", count);
        return ChaosResult.applied(injectionId, ChaosType.OUT_OF_ORDER_EVENT,
                ChaosResult.MODE_IN_PROCESS,
                "next " + count + " events will be overtaken by their successors", detail, clock.now());
    }

    private ChaosResult dropEvent(ChaosRequest request, String injectionId) {
        int count = boundedCount(request);
        EmissionControl control = requireControl(request, ChaosType.DROP_EVENT);
        control.addDropBudget(count);

        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put("runId", control.runId());
        detail.put("count", count);
        return ChaosResult.applied(injectionId, ChaosType.DROP_EVENT, ChaosResult.MODE_IN_PROCESS,
                "next " + count + " events will never be published", detail, clock.now());
    }

    // ---------------------------------------------------------------------------------------
    // Evidence chaos
    // ---------------------------------------------------------------------------------------

    /**
     * Removes the object while leaving the evidence row intact.
     *
     * <p>The row still claims an artifact with a known sha256 exists. Integrity verification and
     * document extraction both have to notice that it does not, rather than quietly treating a
     * missing object as an empty one.
     */
    private ChaosResult deleteEvidence(ChaosRequest request, String injectionId) {
        EvidenceEntity evidence = requireEvidence(request, EvidenceType.DELIVERY_PROOF);
        ObjectStore store = objectStores.getIfAvailable();
        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put(TARGET_EVIDENCE_ID, evidence.getId());
        detail.put("objectKey", evidence.getObjectKey());

        if (store == null || evidence.getObjectKey() == null) {
            return ChaosResult.failed(injectionId, ChaosType.DELETE_EVIDENCE,
                    "cannot delete the artifact",
                    store == null ? "no ObjectStore configured" : "evidence has no objectKey",
                    clock.now());
        }
        store.delete(Buckets.EVIDENCE, evidence.getObjectKey());
        detail.put("rowRetained", true);
        return ChaosResult.applied(injectionId, ChaosType.DELETE_EVIDENCE,
                ChaosResult.MODE_IN_PROCESS,
                "deleted the object behind " + evidence.getId() + "; the evidence row still claims it",
                detail, clock.now());
    }

    /**
     * Tampers with the artifact so its recomputed hash no longer matches the recorded one.
     *
     * <p>Preferred mechanism: overwrite the stored bytes while leaving {@code evidence.sha256}
     * untruthful about them. That is what tampering actually looks like, and the database stays
     * honest. When there is no object store, the documented fallback
     * {@code DB_SHA_MUTATION} corrupts the recorded hash column instead - a weaker but still
     * detectable form, and the injection record says which one was used so nobody has to guess.
     */

    private ChaosResult corruptEvidenceHash(ChaosRequest request, String injectionId) {
        EvidenceEntity evidence = requireEvidence(request, EvidenceType.DELIVERY_PROOF);
        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put(TARGET_EVIDENCE_ID, evidence.getId());
        detail.put("recordedSha256", evidence.getSha256());

        ObjectStore store = objectStores.getIfAvailable();
        if (store != null && evidence.getObjectKey() != null) {
            byte[] original = store.getBytes(Buckets.EVIDENCE, evidence.getObjectKey());
            byte[] tampered = tamper(original);
            Map<String, String> metadata = new LinkedHashMap<>();
            // The recorded sha256 is deliberately left describing the ORIGINAL bytes.
            metadata.put(Buckets.META_SHA256, evidence.getSha256() == null
                    ? Hashes.sha256(original) : evidence.getSha256());
            metadata.put(Buckets.META_EVIDENCE_ID, evidence.getId());
            store.put(Buckets.EVIDENCE, evidence.getObjectKey(), tampered,
                    evidence.getContentType(), metadata);

            detail.put("mechanism", "OBJECT_BYTES_TAMPERED");
            detail.put("actualSha256", Hashes.sha256(tampered));
            return ChaosResult.applied(injectionId, ChaosType.CORRUPT_EVIDENCE_HASH,
                    ChaosResult.MODE_IN_PROCESS,
                    "tampered with the bytes of " + evidence.getId()
                            + "; the recorded sha256 now describes content that is gone",
                    detail, clock.now());
        }

        String corrupted = corruptHex(evidence.getSha256());
        evidence.setSha256(corrupted);
        evidence.setIntegrityOk(Boolean.FALSE);
        evidence.setIntegrityVerifiedAt(clock.now());
        evidenceRepository.save(evidence);

        detail.put("mechanism", "DB_SHA_MUTATION");
        detail.put("corruptedSha256", corrupted);
        return ChaosResult.applied(injectionId, ChaosType.CORRUPT_EVIDENCE_HASH,
                ChaosResult.MODE_IN_PROCESS,
                "no object store available; corrupted the recorded sha256 of " + evidence.getId()
                        + " instead", detail, clock.now());
    }

    /**
     * Ages an artifact out. Backdates {@code expiresAt}, moves the row to EXPIRED and publishes an
     * {@code EvidenceExpired} event so readiness recomputes with the -10 mandatory-expiry penalty.
     */

    private ChaosResult expireEvidence(ChaosRequest request, String injectionId) {
        EvidenceEntity evidence = requireEvidence(request, EvidenceType.DELIVERY_PROOF);
        Instant expiredAt = clock.now().minus(Duration.ofDays(1));

        evidence.setExpiresAt(expiredAt);
        evidence.setStatus(EvidenceStatus.EXPIRED);
        evidenceRepository.save(evidence);
        publishEvidenceExpired(evidence, expiredAt);

        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put(TARGET_EVIDENCE_ID, evidence.getId());
        detail.put("evidenceType", evidence.getType() == null ? null : evidence.getType().name());
        detail.put("expiresAt", expiredAt.toString());
        detail.put("status", EvidenceStatus.EXPIRED.name());
        return ChaosResult.applied(injectionId, ChaosType.EXPIRE_EVIDENCE,
                ChaosResult.MODE_IN_PROCESS,
                "expired " + evidence.getId() + " (" + evidence.getType() + ")", detail, clock.now());
    }

    /**
     * Publishes a second delivery record dated before the parcel was dispatched.
     *
     * <p>Done as a raw event rather than a direct row insert, so the contradiction arrives through
     * the normal ingestion path and every stage downstream - normalisation, state building, gap
     * detection - has to handle it exactly as it would a real conflicting source.
     */
    private ChaosResult conflictingEvidence(ChaosRequest request, String injectionId) {
        String transactionId = request.targetString(TARGET_TRANSACTION_ID);
        if (transactionId == null || transactionId.isBlank()) {
            return ChaosResult.failed(injectionId, ChaosType.CONFLICTING_EVIDENCE,
                    "no target transaction", "target.transactionId is required", clock.now());
        }
        List<EvidenceEntity> existing = evidenceRepository.findByTransactionId(transactionId);
        if (existing.isEmpty()) {
            return ChaosResult.failed(injectionId, ChaosType.CONFLICTING_EVIDENCE,
                    "transaction has no evidence to contradict",
                    "no evidence rows for " + transactionId, clock.now());
        }
        EvidenceEntity reference = existing.stream()
                .filter(evidence -> evidence.getType() == EvidenceType.DELIVERY_PROOF)
                .findFirst()
                .orElse(existing.get(0));
        String merchantId = reference.getMerchantId();

        String evidenceId = Ids.evidence();
        // Backdated a fortnight before the world could plausibly have dispatched anything, so the
        // deliveredAt < dispatchedAt rule fires regardless of the transaction's real timeline.
        Instant impossibleDelivery = reference.getCapturedAt() == null
                ? clock.now().minus(Duration.ofDays(14))
                : reference.getCapturedAt().minus(Duration.ofDays(14));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("deliveryId", Ids.delivery());
        attributes.put("deliveredAt", impossibleDelivery.toString());
        attributes.put("recipientName", "UNVERIFIED RECIPIENT");
        attributes.put("signature", "UNSIGNED");
        attributes.put("provenance", "chaos injection " + injectionId);
        attributes.put("conflictsWith", reference.getId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", evidenceId);
        body.put("merchantId", merchantId);
        body.put(TARGET_TRANSACTION_ID, transactionId);
        body.put("documentType", EvidenceType.DELIVERY_PROOF.name());
        body.put("relatedEntityType", AggregateType.DELIVERY.name());
        body.put("relatedEntityId", attributes.get("deliveryId"));
        body.put("title", "Conflicting proof of delivery (chaos)");
        body.put("summary", "Delivery recorded before dispatch; contradicts " + reference.getId());
        body.put("source", "MERCHANT_PORTAL");
        body.put("capturedAt", impossibleDelivery.toString());
        body.put("effectiveFrom", impossibleDelivery.toString());
        body.put("attributes", attributes);

        boolean published = emitter.publish(rawEnvelope(EventType.EvidenceAdded, merchantId,
                evidenceId, injectionId, body), evidenceId);

        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put(TARGET_TRANSACTION_ID, transactionId);
        detail.put("conflictsWith", reference.getId());
        detail.put("newEvidenceId", evidenceId);
        detail.put("deliveredAt", impossibleDelivery.toString());
        detail.put("published", published);
        return published
                ? ChaosResult.applied(injectionId, ChaosType.CONFLICTING_EVIDENCE,
                        ChaosResult.MODE_IN_PROCESS,
                        "published a delivery proof for " + transactionId + " dated before dispatch",
                        detail, clock.now())
                : ChaosResult.failed(injectionId, ChaosType.CONFLICTING_EVIDENCE,
                        "could not publish the conflicting evidence event",
                        "Kafka publish failed", clock.now());
    }

    // ---------------------------------------------------------------------------------------
    // Infrastructure chaos
    // ---------------------------------------------------------------------------------------

    private ChaosResult killWorker(ChaosRequest request, String injectionId) {
        String service = serviceTarget(request, "case-orchestrator-service");
        WorkerControl.ControlOutcome outcome = workerControl.kill(service);
        return fromControl(injectionId, ChaosType.KILL_WORKER, service, outcome,
                "killed " + service + "; Temporal must recover the workflow");
    }

    private ChaosResult restartConsumer(ChaosRequest request, String injectionId) {
        String service = serviceTarget(request, "readiness-worker");
        WorkerControl.ControlOutcome outcome = workerControl.restart(service);
        return fromControl(injectionId, ChaosType.RESTART_CONSUMER, service, outcome,
                "restarted " + service + "; the consumer group rebalances and redelivers");
    }

    private ChaosResult slowConsumer(ChaosRequest request, String injectionId) {
        String service = serviceTarget(request, "state-builder-worker");
        long delayMillis = boundedDelay(request, 5_000L);
        WorkerControl.ControlOutcome outcome = workerControl.slow(service, delayMillis);
        return fromControl(injectionId, ChaosType.SLOW_CONSUMER, service, outcome,
                "slowed " + service + " for " + delayMillis + " ms; watch consumer lag");
    }

    private ChaosResult fromControl(String injectionId, ChaosType type, String service,
                                    WorkerControl.ControlOutcome outcome, String summary) {
        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put(TARGET_SERVICE, service);
        detail.put("mechanism", outcome.mode());
        detail.put("detail", outcome.detail());
        if (!outcome.applied()) {
            return new ChaosResult(injectionId, type, ChaosInjectionEntity.STATUS_FAILED,
                    outcome.mode(), summary, detail, clock.now(), outcome.detail());
        }
        return ChaosResult.applied(injectionId, type, outcome.mode(), summary, detail, clock.now());
    }

    // ---------------------------------------------------------------------------------------
    // Workload chaos
    // ---------------------------------------------------------------------------------------

    private ChaosResult replayEvents(ChaosRequest request, String injectionId) {
        String topic = request.targetString(TARGET_TOPIC);
        Long fromOffset = request.targetLong(TARGET_FROM_OFFSET);
        String timestamp = request.targetString(TARGET_FROM_TIMESTAMP);
        Instant fromTimestamp = timestamp == null ? null : Instant.parse(timestamp);
        if (fromOffset == null && fromTimestamp == null) {
            fromOffset = 0L; // replay the topic from the beginning
        }

        ReplayResult replay = replayService.replay(new ReplayRequest(
                topic == null ? Topics.RAW_EVENTS : topic,
                fromOffset, fromTimestamp, request.targetString(TARGET_MERCHANT_ID),
                request.count(), null));

        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put("replayId", replay.replayId());
        detail.put(TARGET_TOPIC, replay.topic());
        detail.put("recordsRead", replay.recordsRead());
        detail.put("recordsRepublished", replay.recordsRepublished());
        detail.put("startOffsets", replay.startOffsets());
        detail.put("endOffsets", replay.endOffsets());
        detail.put("durationMs", replay.durationMillis());
        return ChaosResult.applied(injectionId, ChaosType.REPLAY_EVENTS, ChaosResult.MODE_IN_PROCESS,
                "replayed " + replay.recordsRead() + " records from " + replay.topic()
                        + " (" + replay.note() + ")", detail, clock.now());
    }

    /**
     * Opens a dispute out of nowhere on an existing transaction.
     *
     * <p>This is the cold-start path for the case pipeline: no simulation run needs to be in
     * flight, and the readiness that was computed hours ago is suddenly load-bearing.
     */
    private ChaosResult injectDispute(ChaosRequest request, String injectionId) {
        String transactionId = request.targetString(TARGET_TRANSACTION_ID);
        if (transactionId == null || transactionId.isBlank()) {
            return ChaosResult.failed(injectionId, ChaosType.INJECT_DISPUTE,
                    "no target transaction", "target.transactionId is required", clock.now());
        }
        String merchantId = request.targetString(TARGET_MERCHANT_ID);
        if (merchantId == null) {
            merchantId = evidenceRepository.findByTransactionId(transactionId).stream()
                    .map(EvidenceEntity::getMerchantId)
                    .findFirst()
                    .orElse(null);
        }
        if (merchantId == null) {
            return ChaosResult.failed(injectionId, ChaosType.INJECT_DISPUTE,
                    "cannot determine the merchant",
                    "pass target.merchantId, or use a transaction that has evidence", clock.now());
        }

        DisputeReasonCode reasonCode = parseReasonCode(request.targetString(TARGET_REASON_CODE));
        String disputeId = Ids.dispute();
        Instant openedAt = clock.now();
        Instant dueBy = openedAt.plus(Duration.ofDays(7));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disputeId", disputeId);
        body.put(TARGET_TRANSACTION_ID, transactionId);
        body.put(TARGET_MERCHANT_ID, merchantId);
        body.put(TARGET_REASON_CODE, reasonCode.name());
        body.put("status", "OPEN");
        body.put("network", "VISA");
        body.put("caseNumber", "CHAOS-" + injectionId.substring(0, 8).toUpperCase(Locale.ROOT));
        body.put("openedAt", openedAt.toString());
        body.put("evidenceDueBy", dueBy.toString());
        body.put("injectedBy", "simulator-chaos");

        boolean published = emitter.publish(rawEnvelope(EventType.DisputeCreated, merchantId,
                disputeId, injectionId, body), disputeId);

        Map<String, Object> detail = ChaosResult.detailMap();
        detail.put("disputeId", disputeId);
        detail.put(TARGET_TRANSACTION_ID, transactionId);
        detail.put(TARGET_MERCHANT_ID, merchantId);
        detail.put(TARGET_REASON_CODE, reasonCode.name());
        detail.put("evidenceDueBy", dueBy.toString());
        detail.put("published", published);
        return published
                ? ChaosResult.applied(injectionId, ChaosType.INJECT_DISPUTE,
                        ChaosResult.MODE_IN_PROCESS,
                        "opened " + reasonCode + " dispute " + disputeId + " on " + transactionId,
                        detail, clock.now())
                : ChaosResult.failed(injectionId, ChaosType.INJECT_DISPUTE,
                        "could not publish the dispute event", "Kafka publish failed", clock.now());
    }

    // =======================================================================================
    // Helpers
    // =======================================================================================

    private ChaosInjectionEntity persistRequested(ChaosRequest request) {
        ChaosInjectionEntity entity = new ChaosInjectionEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setRunId(resolveRunId(request));
        // merchant_id has a foreign key to merchants; only set it when the caller named one, and
        // never invent one, or a chaos injection could fail on a constraint it has no business
        // caring about.
        entity.setMerchantId(request.targetString(TARGET_MERCHANT_ID));
        entity.setType(request.type());
        entity.setStatus(ChaosInjectionEntity.STATUS_REQUESTED);
        entity.setTarget(request.target());
        entity.setDelayMs(request.delayMs());
        entity.setEventCount(request.count());
        entity.setActor(request.actor());
        entity.setInjectedAt(clock.now());
        return injections.save(entity);
    }

    private void persistOutcome(String injectionId, ChaosResult result) {
        injections.findById(injectionId).ifPresent(entity -> {
            entity.setStatus(result.status());
            entity.setCompletedAt(result.at() == null ? clock.now() : result.at());
            Map<String, Object> stored = new LinkedHashMap<>(result.detail());
            stored.put("mode", result.mode());
            stored.put("summary", result.summary());
            entity.setResult(stored);
            entity.setErrorMessage(abbreviate(result.errorMessage()));
            injections.save(entity);
        });
    }

    /** Resolves the run this injection belongs to: explicit, else any run currently in flight. */
    private String resolveRunId(ChaosRequest request) {
        if (request.runId() != null && !request.runId().isBlank()) {
            return request.runId();
        }
        String targeted = request.targetString(TARGET_RUN_ID);
        if (targeted != null) {
            return targeted;
        }
        return runner.anyActiveControl().map(EmissionControl::runId).orElse(null);
    }

    private Optional<EmissionControl> controlFor(ChaosRequest request) {
        String runId = resolveRunId(request);
        if (runId != null) {
            Optional<EmissionControl> control = runner.control(runId);
            if (control.isPresent()) {
                return control;
            }
        }
        return runner.anyActiveControl();
    }

    private EmissionControl requireControl(ChaosRequest request, ChaosType type) {
        return controlFor(request).orElseThrow(() -> new ValidationException(
                type + " needs a simulation run in flight; start one with POST /sim/v1/runs"));
    }

    private List<SimEvent> retainedTraffic(ChaosRequest request, int count) {
        String runId = resolveRunId(request);
        List<SimEvent> stream = runId == null ? List.of() : runner.retainedStream(runId);
        if (stream.isEmpty()) {
            stream = runner.anyRetainedStream().orElse(List.of());
        }
        return stream.size() <= count ? stream : List.copyOf(stream.subList(0, count));
    }

    /** Resolves the evidence to act on: explicit id, else the first artifact of a transaction. */
    private EvidenceEntity requireEvidence(ChaosRequest request, EvidenceType preferred) {
        String evidenceId = request.targetString(TARGET_EVIDENCE_ID);
        if (evidenceId != null && !evidenceId.isBlank()) {
            return evidenceRepository.findById(evidenceId).orElseThrow(
                    () -> new ValidationException("no evidence with id " + evidenceId));
        }
        String transactionId = request.targetString(TARGET_TRANSACTION_ID);
        if (transactionId == null || transactionId.isBlank()) {
            throw new ValidationException("target.evidenceId or target.transactionId is required");
        }
        List<EvidenceEntity> candidates = evidenceRepository.findByTransactionId(transactionId);
        if (candidates.isEmpty()) {
            throw new ValidationException("transaction " + transactionId + " has no evidence");
        }
        if (preferred != null) {
            return candidates.stream()
                    .filter(evidence -> evidence.getType() == preferred)
                    .findFirst()
                    .orElse(candidates.get(0));
        }
        return candidates.get(0);
    }

    /** Wraps a synthetic body in the same raw envelope shape the generator produces. */
    private RawEventEnvelope rawEnvelope(EventType type, String merchantId, String aggregateId,
                                         String injectionId, Map<String, Object> body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(EventHeaders.EVENT_TYPE, type.name());
        headers.put(EventHeaders.MERCHANT_ID, merchantId);
        headers.put(EventHeaders.CORRELATION_ID, injectionId);
        headers.put(EventHeaders.SCHEMA_VERSION, "1");
        headers.put("pdei-chaos-injection-id", injectionId);

        return new RawEventEnvelope(
                Ids.eventId(),
                SourceVocabulary.systemFor(type),
                SourceVocabulary.sourceEventType(type),
                merchantId,
                clock.now(),
                "chaos:" + injectionId + ":" + aggregateId,
                headers,
                Json.tree(body));
    }

    /** Announces an expiry on {@code pdei.evidence.events.v1} so readiness recomputes. */
    private void publishEvidenceExpired(EvidenceEntity evidence, Instant expiredAt) {
        EventPublisherPort publisher = publishers.getIfAvailable();
        if (publisher == null) {
            log.debug("no EventPublisherPort; EvidenceExpired for {} not announced", evidence.getId());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("evidenceId", evidence.getId());
        payload.put(TARGET_TRANSACTION_ID, evidence.getTransactionId());
        payload.put("evidenceType", evidence.getType() == null ? null : evidence.getType().name());
        payload.put("expiresAt", expiredAt.toString());
        payload.put("reason", "CHAOS_EXPIRE_EVIDENCE");

        CanonicalEvent event = CanonicalEvent.builder()
                .eventId(Ids.eventId())
                .eventType(EventType.EvidenceExpired)
                .aggregateType(AggregateType.EVIDENCE)
                .aggregateId(evidence.getId())
                .merchantId(evidence.getMerchantId())
                .occurredAt(expiredAt)
                .observedAt(clock.now())
                .source(EventSource.SIMULATOR)
                .idempotencyKey("chaos:expire:" + evidence.getId() + ":" + expiredAt.toEpochMilli())
                .payloadFrom(payload)
                .build();
        publisher.publish(Topics.EVIDENCE_EVENTS, event);
    }

    private int boundedCount(ChaosRequest request) {
        int requested = request.countOrDefault(10);
        return Math.max(1, Math.min(properties.getChaos().getMaxEventCount(), requested));
    }

    private long boundedDelay(ChaosRequest request, long fallbackMillis) {
        long requested = request.delayOrDefault(fallbackMillis);
        return Math.max(0L, Math.min(properties.getChaos().getMaxDelay().toMillis(), requested));
    }

    private String serviceTarget(ChaosRequest request, String fallback) {
        String service = request.targetString(TARGET_SERVICE);
        return service == null || service.isBlank() ? fallback : service.strip();
    }

    private static DisputeReasonCode parseReasonCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return DisputeReasonCode.GOODS_NOT_RECEIVED;
        }
        try {
            return DisputeReasonCode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown dispute reason code: " + raw);
        }
    }

    /** Flips one byte in the middle so the artifact is still readable but no longer authentic. */
    private static byte[] tamper(byte[] original) {
        byte[] copy = original.clone();
        if (copy.length == 0) {
            return "TAMPERED BY CHAOS INJECTION".getBytes(StandardCharsets.UTF_8);
        }
        int index = copy.length / 2;
        copy[index] = (byte) (copy[index] ^ 0x5A);
        return copy;
    }

    /** Deterministically mangles a hex digest so it cannot match anything real. */
    private static String corruptHex(String sha256) {
        if (sha256 == null || sha256.length() < 8) {
            return "0".repeat(64);
        }
        char[] chars = sha256.toCharArray();
        chars[0] = chars[0] == 'f' ? '0' : 'f';
        chars[chars.length - 1] = chars[chars.length - 1] == '0' ? 'f' : '0';
        return new String(chars);
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
