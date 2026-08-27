package com.laserpay.pdei.docproc.service;

import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.core.storage.Buckets;
import com.laserpay.pdei.core.storage.ObjectStat;
import com.laserpay.pdei.core.storage.ObjectStore;
import com.laserpay.pdei.docproc.config.DocProcProperties;
import com.laserpay.pdei.docproc.extract.ExtractionFailedException;
import com.laserpay.pdei.docproc.extract.ExtractionRequest;
import com.laserpay.pdei.docproc.extract.ExtractionResult;
import com.laserpay.pdei.docproc.extract.ExtractorRegistry;
import com.laserpay.pdei.docproc.extract.TextNormalizer;
import com.laserpay.pdei.persistence.entity.EvidenceEntity;
import com.laserpay.pdei.persistence.repository.EvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns an evidence artifact into searchable, verifiable text.
 *
 * <p>The pipeline, in order, with the reason each step exists:
 * <ol>
 *   <li><strong>Load the evidence row.</strong> The object key, the recorded sha256 and the
 *       merchant all come from Postgres, never from the event payload - an event can be replayed
 *       from a stale snapshot, the row cannot.</li>
 *   <li><strong>Size check before read.</strong> {@code stat} first, {@code get} second. A 2 GB
 *       object never enters the heap just to be rejected afterwards.</li>
 *   <li><strong>Recompute sha256.</strong> The hash is over the bytes actually parsed. A mismatch
 *       against {@code evidence.sha256} means the artifact changed under us, so it is quarantined
 *       and marked {@code integrityOk = false}: text from a tampered artifact must never enter
 *       the search index, because search results become citations and citations become
 *       representment claims.</li>
 *   <li><strong>Extract with a timeout.</strong> On the extraction pool, so a hung parser costs
 *       one quarantined document instead of a stalled Kafka partition.</li>
 *   <li><strong>Write the FTS column.</strong> {@code evidence.extracted_text} is weight D of the
 *       {@code search_vector} maintained by the V10 trigger; writing the column is what makes the
 *       document searchable. No SQL here touches {@code search_vector} directly.</li>
 *   <li><strong>Emit an EVIDENCE event.</strong> Platform contract 7: any EVIDENCE event triggers
 *       readiness recomputation, and readiness is what the merchant actually looks at.</li>
 * </ol>
 *
 * <p>This service never mutates financial state: it writes text, metadata and integrity flags on
 * an evidence row and nothing else. Amounts, statuses and lifecycle transitions belong to
 * state-builder-worker and evidence-core.
 */
@Service
public class DocumentProcessingService {

    /**
     * Marker written into every event this service publishes. The Kafka consumer skips events
     * carrying it, which is what stops "publish to the topic you consume" from becoming an
     * infinite loop. Belt and braces alongside the SKIPPED_UNCHANGED short-circuit.
     */
    public static final String EMITTED_BY = "document-processor-service";
    public static final String PAYLOAD_EMITTED_BY = "emittedBy";
    public static final String PAYLOAD_ACTION = "action";
    public static final String ACTION_TEXT_EXTRACTED = "TEXT_EXTRACTED";

    /** Metadata key holding the sha256 of the bytes the current text was extracted from. */
    private static final String META_SHA = "docproc.sha256";
    private static final String META_EXTRACTOR = "docproc.extractor";
    private static final String META_EXTRACTED_AT = "docproc.extractedAt";
    private static final String META_CHARACTERS = "docproc.characters";
    private static final String META_PAGE_COUNT = "docproc.pageCount";
    private static final String META_TRUNCATED = "docproc.truncated";
    private static final String META_WARNINGS = "docproc.warnings";

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final EvidenceRepository evidenceRepository;
    private final ObjectProvider<ObjectStore> objectStores;
    private final ExtractorRegistry registry;
    private final QuarantineService quarantine;
    private final DocProcStats stats;
    private final ObjectProvider<EventPublisherPort> publishers;
    private final ExecutorService extractionExecutor;
    private final DocProcProperties properties;
    private final Clocks clock;

    public DocumentProcessingService(EvidenceRepository evidenceRepository,
                                     ObjectProvider<ObjectStore> objectStores,
                                     ExtractorRegistry registry,
                                     QuarantineService quarantine,
                                     DocProcStats stats,
                                     ObjectProvider<EventPublisherPort> publishers,
                                     ExecutorService extractionExecutor,
                                     DocProcProperties properties,
                                     Clocks clock) {
        this.evidenceRepository = evidenceRepository;
        this.objectStores = objectStores;
        this.registry = registry;
        this.quarantine = quarantine;
        this.stats = stats;
        this.publishers = publishers;
        this.extractionExecutor = extractionExecutor;
        this.properties = properties;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------------------
    // Ad-hoc extraction: POST /docproc/v1/extract
    // -------------------------------------------------------------------------------------

    /**
     * Extract an object straight from the store without touching any evidence row.
     *
     * <p>This is the diagnostic path behind {@code POST /docproc/v1/extract}: "what would you get
     * out of this key". Read-only by construction - no database write, no event.
     */
    public ExtractionResult extractObject(String bucket, String objectKey) {
        String targetBucket = (bucket == null || bucket.isBlank()) ? Buckets.EVIDENCE : bucket;
        ObjectStore store = requireObjectStore();

        ObjectStat stat = store.stat(targetBucket, objectKey);
        if (stat.sizeBytes() > properties.getMaxObjectBytes()) {
            throw new ExtractionFailedException("size-guard",
                    "object " + objectKey + " is " + stat.sizeBytes() + " bytes, over the "
                            + properties.getMaxObjectBytes() + " byte ceiling");
        }

        byte[] content = store.getBytes(targetBucket, objectKey);
        stats.bytesRead(content.length);
        ExtractionRequest request = new ExtractionRequest(objectKey, null, stat.contentType(),
                content, null);
        ExtractionResult result = runWithTimeout(request);
        return capText(result);
    }

    // -------------------------------------------------------------------------------------
    // Evidence-driven extraction: Kafka consumer and POST /docproc/v1/reprocess/{evidenceId}
    // -------------------------------------------------------------------------------------

    /**
     * Process the artifact behind one evidence row.
     *
     * @param evidenceId    the artifact to process
     * @param causationId   originating event id for the emitted event's causal chain, may be null
     * @param force         re-extract even when the bytes are unchanged (the reprocess endpoint)
     */
    @Transactional
    public ProcessingOutcome processEvidence(String evidenceId, String causationId, boolean force) {
        Instant startedAt = clock.now();
        Optional<EvidenceEntity> found = evidenceRepository.findById(evidenceId);
        if (found.isEmpty()) {
            log.info("evidence {} not found; nothing to extract", evidenceId);
            return ProcessingOutcome.of(evidenceId, ProcessingOutcome.Status.NOT_FOUND,
                    "no evidence row with id " + evidenceId, startedAt);
        }
        EvidenceEntity evidence = found.get();

        String objectKey = evidence.getObjectKey();
        if (objectKey == null || objectKey.isBlank()) {
            return ProcessingOutcome.of(evidenceId, ProcessingOutcome.Status.SKIPPED_NO_OBJECT,
                    "evidence has no object key (derived evidence carries no artifact)", startedAt);
        }

        ObjectStore store = objectStores.getIfAvailable();
        if (store == null) {
            quarantine.quarantine(evidenceId, objectKey, QuarantineService.Reason.STORAGE_ERROR,
                    "no ObjectStore bean is configured");
            return quarantined(evidenceId, "object store unavailable", startedAt);
        }

        byte[] content;
        String declaredContentType;
        try {
            ObjectStat stat = store.stat(Buckets.EVIDENCE, objectKey);
            declaredContentType = stat.contentType();
            if (stat.sizeBytes() > properties.getMaxObjectBytes()) {
                quarantine.quarantine(evidenceId, objectKey, QuarantineService.Reason.OVERSIZE,
                        stat.sizeBytes() + " bytes exceeds the " + properties.getMaxObjectBytes()
                                + " byte ceiling");
                return quarantined(evidenceId, "artifact too large", startedAt);
            }
            content = store.getBytes(Buckets.EVIDENCE, objectKey);
        } catch (RuntimeException e) {
            QuarantineService.Reason reason = looksMissing(e)
                    ? QuarantineService.Reason.OBJECT_MISSING
                    : QuarantineService.Reason.STORAGE_ERROR;
            quarantine.quarantine(evidenceId, objectKey, reason, e.toString());
            return quarantined(evidenceId, "could not read artifact: " + e.getMessage(), startedAt);
        }
        stats.bytesRead(content.length);

        String sha256 = Hashes.sha256(content);
        boolean integrityOk = evidence.getSha256() == null || evidence.getSha256().isBlank()
                || evidence.getSha256().equalsIgnoreCase(sha256);
        if (!integrityOk) {
            quarantine.quarantine(evidenceId, objectKey, QuarantineService.Reason.HASH_MISMATCH,
                    "recorded=" + evidence.getSha256() + " recomputed=" + sha256);
            return new ProcessingOutcome(evidenceId, ProcessingOutcome.Status.QUARANTINED, null,
                    sha256, 0, 0, false, List.of("sha256 mismatch"),
                    "artifact hash does not match the evidence record", clock.now());
        }

        if (!force && !properties.isReextractUnchanged() && alreadyExtracted(evidence, sha256)) {
            log.debug("evidence {} already extracted for sha256 {}", evidenceId, sha256);
            return ProcessingOutcome.of(evidenceId, ProcessingOutcome.Status.SKIPPED_UNCHANGED,
                    "text already extracted from these bytes", startedAt);
        }

        ExtractionResult result;
        try {
            ExtractionRequest request = new ExtractionRequest(objectKey, evidence.getFilename(),
                    firstNonBlank(evidence.getContentType(), declaredContentType), content, evidenceId);
            result = capText(runWithTimeout(request));
        } catch (ExtractionTimeoutException e) {
            quarantine.quarantine(evidenceId, objectKey, QuarantineService.Reason.TIMEOUT, e.getMessage());
            return quarantined(evidenceId, "extraction timed out", startedAt);
        } catch (ExtractionFailedException e) {
            quarantine.quarantine(evidenceId, objectKey, QuarantineService.Reason.UNDECODABLE,
                    e.getMessage());
            return quarantined(evidenceId, "artifact could not be decoded", startedAt);
        }

        if (result.isEmpty()) {
            // Metadata is still worth keeping: page count and producer are provenance even when
            // there is no text layer to index (a scanned delivery note, for example).
            persist(evidence, result, sha256, true);
            quarantine.quarantine(evidenceId, objectKey, QuarantineService.Reason.NO_TEXT,
                    String.join("; ", result.warnings()));
            return new ProcessingOutcome(evidenceId, ProcessingOutcome.Status.QUARANTINED,
                    result.extractor(), sha256, result.pageCount(), 0, true, result.warnings(),
                    "no text extracted; metadata retained", clock.now());
        }

        persist(evidence, result, sha256, false);
        publishExtracted(evidence, result, sha256, causationId);

        log.info("evidence {} extracted by {}: {} chars, {} pages",
                evidenceId, result.extractor(), result.characterCount(), result.pageCount());
        return new ProcessingOutcome(evidenceId, ProcessingOutcome.Status.EXTRACTED,
                result.extractor(), sha256, result.pageCount(), result.characterCount(), true,
                result.warnings(), "extracted", clock.now());
    }

    // -------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------

    /** True when the current text was produced from exactly these bytes. */
    private boolean alreadyExtracted(EvidenceEntity evidence, String sha256) {
        if (evidence.getExtractedText() == null || evidence.getExtractedText().isBlank()) {
            return false;
        }
        Map<String, Object> metadata = evidence.getMetadata();
        Object recorded = metadata == null ? null : metadata.get(META_SHA);
        return sha256.equals(recorded);
    }

    /**
     * Writes the extraction onto the evidence row.
     *
     * <p>Only text, metadata and integrity fields are touched. The V10 trigger recomputes
     * {@code search_vector} from {@code extracted_text} on update, so nothing here has to know
     * about the tsvector.
     */
    private void persist(EvidenceEntity evidence, ExtractionResult result, String sha256, boolean textless) {
        quarantine.clearMarks(evidence);

        if (!textless) {
            evidence.setExtractedText(result.text());
        }

        Map<String, Object> metadata = evidence.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(evidence.getMetadata());
        metadata.put(META_SHA, sha256);
        metadata.put(META_EXTRACTOR, result.extractor());
        metadata.put(META_EXTRACTED_AT, result.extractedAt().toString());
        metadata.put(META_CHARACTERS, result.characterCount());
        metadata.put(META_PAGE_COUNT, result.pageCount());
        metadata.put(META_TRUNCATED, result.truncated());
        if (!result.warnings().isEmpty()) {
            metadata.put(META_WARNINGS, List.copyOf(result.warnings()));
        } else {
            metadata.remove(META_WARNINGS);
        }
        metadata.putAll(result.metadata());
        evidence.setMetadata(metadata);

        if (evidence.getContentType() == null || evidence.getContentType().isBlank()) {
            evidence.setContentType(result.contentType());
        }
        if (evidence.getSizeBytes() == null || evidence.getSizeBytes() <= 0) {
            evidence.setSizeBytes(result.sizeBytes());
        }
        if (evidence.getSha256() == null || evidence.getSha256().isBlank()) {
            evidence.setSha256(sha256);
        }
        evidence.setIntegrityOk(Boolean.TRUE);
        evidence.setIntegrityVerifiedAt(clock.now());

        evidenceRepository.save(evidence);
    }

    /**
     * Publishes the "this artifact is now searchable" fact onto {@code pdei.evidence.events.v1}.
     *
     * <p>{@link EventType} has no {@code EvidenceUpdated} member - the enum is frozen by
     * docs/SHARED-LIBRARY-API.md section 1.3 - so the update is carried as
     * {@link EventType#EvidenceAdded} with {@code action = TEXT_EXTRACTED}. That is the correct
     * downstream semantics regardless: platform contract 7 says <em>any</em> EVIDENCE event
     * triggers readiness recomputation, which is exactly what should happen when an artifact's
     * text becomes available. The {@code idempotencyKey} is derived from the evidence id and the
     * content hash, so re-extraction of unchanged bytes collapses to one logical fact downstream.
     */
    private void publishExtracted(EvidenceEntity evidence, ExtractionResult result,
                                  String sha256, String causationId) {
        if (!properties.isPublishEvidenceEvents()) {
            return;
        }
        EventPublisherPort publisher = publishers.getIfAvailable();
        if (publisher == null) {
            log.debug("no EventPublisherPort configured; skipping evidence-updated event");
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_EMITTED_BY, EMITTED_BY);
        payload.put(PAYLOAD_ACTION, ACTION_TEXT_EXTRACTED);
        payload.put("evidenceId", evidence.getId());
        payload.put("transactionId", evidence.getTransactionId());
        payload.put("evidenceType", evidence.getType() == null ? null : evidence.getType().name());
        payload.put("status", evidence.getStatus() == null ? null : evidence.getStatus().name());
        payload.put("version", evidence.getCurrentVersion());
        payload.put("sha256", sha256);
        payload.put("extractor", result.extractor());
        payload.put("contentType", result.contentType());
        payload.put("sizeBytes", result.sizeBytes());
        payload.put("pageCount", result.pageCount());
        payload.put("characters", result.characterCount());
        payload.put("truncated", result.truncated());
        payload.put("warnings", result.warnings());
        payload.put("extractedAt", result.extractedAt().toString());

        Instant now = clock.now();
        CanonicalEvent event = CanonicalEvent.builder()
                .eventId(Ids.eventId())
                .eventType(EventType.EvidenceAdded)
                .aggregateType(AggregateType.EVIDENCE)
                .aggregateId(evidence.getId())
                .merchantId(evidence.getMerchantId())
                .causationId(causationId)
                .occurredAt(now)
                .observedAt(now)
                .source(EventSource.INTERNAL)
                .idempotencyKey("docproc:text-extracted:" + evidence.getId() + ":" + sha256)
                .payload(Json.tree(payload))
                .build();

        publisher.publish(Topics.EVIDENCE_EVENTS, event);
    }

    /** Applies the FTS character ceiling; Tika's own write limit is a separate, larger guard. */
    private ExtractionResult capText(ExtractionResult result) {
        int max = properties.getMaxTextChars();
        if (max <= 0 || result.text().length() <= max) {
            return result;
        }
        String capped = TextNormalizer.truncate(result.text(), max);
        return result.withText(capped, true)
                .withWarning("text truncated to " + max + " characters for the FTS column");
    }

    /**
     * Runs the extraction on the bounded pool with a wall-clock budget.
     *
     * <p>{@code future.cancel(true)} interrupts the parser thread. Tika and PDFBox both honour
     * interruption at I/O boundaries; a parser that ignores it leaks one pool thread, which is
     * strictly better than leaking the consumer thread and stalling a partition.
     */
    private ExtractionResult runWithTimeout(ExtractionRequest request) {
        Future<ExtractionResult> future = extractionExecutor.submit(() -> registry.extract(request));
        long timeoutMillis = Math.max(1L, properties.getExtractionTimeout().toMillis());
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ExtractionTimeoutException("extraction of " + request.objectKey()
                    + " exceeded " + timeoutMillis + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ExtractionTimeoutException("interrupted while extracting " + request.objectKey());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ExtractionFailedException failed) {
                throw failed;
            }
            throw new ExtractionFailedException("registry",
                    "extraction of " + request.objectKey() + " failed: " + cause, cause);
        }
    }

    private ObjectStore requireObjectStore() {
        ObjectStore store = objectStores.getIfAvailable();
        if (store == null) {
            throw new ExtractionFailedException("storage",
                    "no ObjectStore bean configured; is MinIO reachable?");
        }
        return store;
    }

    private ProcessingOutcome quarantined(String evidenceId, String message, Instant startedAt) {
        return ProcessingOutcome.of(evidenceId, ProcessingOutcome.Status.QUARANTINED, message, startedAt);
    }

    /**
     * MinIO surfaces a missing key as an {@code ErrorResponseException} carrying "NoSuchKey".
     * Matched on the message so this module does not take a compile dependency on the MinIO
     * exception hierarchy for what is a logging distinction.
     */
    private static boolean looksMissing(RuntimeException e) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = e; t != null && messages.size() < 5; t = t.getCause()) {
            if (t.getMessage() != null) {
                messages.add(t.getMessage());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return messages.stream()
                .anyMatch(m -> m.contains("NoSuchKey") || m.contains("does not exist")
                        || m.contains("Not Found") || m.contains("NoSuchObject"));
    }

    private static String firstNonBlank(String first, String second) {
        return (first != null && !first.isBlank()) ? first : second;
    }

    /** Raised when an extraction exceeds {@code pdei.docproc.extraction-timeout}. */
    static final class ExtractionTimeoutException extends RuntimeException {
        ExtractionTimeoutException(String message) {
            super(message);
        }
    }
}
