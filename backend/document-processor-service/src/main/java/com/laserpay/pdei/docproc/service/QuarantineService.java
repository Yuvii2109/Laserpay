package com.laserpay.pdei.docproc.service;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.docproc.config.DocProcProperties;
import com.laserpay.pdei.persistence.entity.EvidenceEntity;
import com.laserpay.pdei.persistence.repository.EvidenceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The quarantine path: what happens to an artifact that cannot be turned into trustworthy text.
 *
 * <p>Three rules shape this:
 * <ol>
 *   <li><strong>Nothing is deleted.</strong> Evidence provenance is the product (reference
 *       section 12). A quarantined artifact keeps its object, its row and its version history;
 *       what changes is that its text never enters the search index.</li>
 *   <li><strong>The failure is visible.</strong> The reason is stamped into
 *       {@code evidence.metadata} under {@code docproc.quarantine.*} and surfaced by
 *       {@code GET /docproc/v1/stats}, so "why is this document not searchable" has an answer
 *       that does not require reading logs.</li>
 *   <li><strong>The consumer keeps moving.</strong> Quarantine is a normal outcome, not an
 *       exception: the Kafka offset commits and the partition does not stall behind one bad
 *       PDF.</li>
 * </ol>
 *
 * <p>A quarantined artifact is retried by {@code POST /docproc/v1/reprocess/{evidenceId}}, which
 * is the manual escape hatch once the underlying cause (a bad upload, a missing object, a
 * corrected hash) has been fixed.
 */
@Service
public class QuarantineService {

    /** Reasons an artifact lands in quarantine. Stable strings: they appear in the stats API. */
    public enum Reason {
        /** Object exceeds {@code pdei.docproc.max-object-bytes}. */
        OVERSIZE,
        /** {@code evidence.object_key} points at an object the store does not have. */
        OBJECT_MISSING,
        /** Recomputed sha256 does not match the hash recorded on the evidence row. */
        HASH_MISMATCH,
        /** Every extractor refused or failed on these bytes. */
        UNDECODABLE,
        /** Extraction exceeded {@code pdei.docproc.extraction-timeout}. */
        TIMEOUT,
        /** Parsed cleanly but produced no text - typically a scanned image, and OCR is out of scope. */
        NO_TEXT,
        /** Object store unreachable or refused the read. */
        STORAGE_ERROR
    }

    /**
     * One quarantine record.
     *
     * @param evidenceId artifact that was quarantined
     * @param objectKey  MinIO key, when known
     * @param reason     why
     * @param detail     exception message or size figures
     * @param at         when
     */
    public record QuarantineEntry(String evidenceId, String objectKey, Reason reason,
                                  String detail, Instant at) {
    }

    private static final Logger log = LoggerFactory.getLogger(QuarantineService.class);
    private static final String METRIC = "pdei_docproc_quarantined_total";

    private final EvidenceRepository evidenceRepository;
    private final MeterRegistry meterRegistry;
    private final Clocks clock;
    private final int historySize;

    /** Bounded FIFO of the most recent entries; guarded by its own monitor. */
    private final Deque<QuarantineEntry> history = new ArrayDeque<>();

    public QuarantineService(EvidenceRepository evidenceRepository,
                             MeterRegistry meterRegistry,
                             Clocks clock,
                             DocProcProperties properties) {
        this.evidenceRepository = evidenceRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.historySize = Math.max(1, properties.getQuarantineHistorySize());
    }

    /**
     * Record a quarantine decision and stamp it on the evidence row.
     *
     * <p>{@code REQUIRES_NEW}: the surrounding processing transaction may be about to roll back
     * (a storage error, an optimistic lock clash), and the quarantine marker is exactly the fact
     * that must survive that rollback. Losing it would leave an artifact that is silently absent
     * from search with nothing anywhere saying why.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuarantineEntry quarantine(String evidenceId, String objectKey, Reason reason, String detail) {
        Instant now = clock.now();
        QuarantineEntry entry = new QuarantineEntry(evidenceId, objectKey, reason,
                detail == null ? "" : detail, now);

        remember(entry);
        meterRegistry.counter(METRIC, "reason", reason.name()).increment();
        log.warn("evidence {} quarantined ({}): {} [objectKey={}]", evidenceId, reason, detail, objectKey);

        if (evidenceId == null || evidenceId.isBlank()) {
            return entry; // ad-hoc extraction through POST /extract: nothing to stamp
        }
        evidenceRepository.findById(evidenceId).ifPresent(evidence -> stamp(evidence, entry));
        return entry;
    }

    /**
     * Clear the quarantine marks. Called at the start of a successful extraction so a document
     * that was fixed and reprocessed does not keep a stale failure attached to it forever.
     */
    void clearMarks(EvidenceEntity evidence) {
        Map<String, Object> metadata = evidence.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        Map<String, Object> updated = new LinkedHashMap<>(metadata);
        boolean removed = updated.keySet().removeIf(key -> key.startsWith("docproc.quarantine."));
        if (removed) {
            evidence.setMetadata(updated);
        }
    }

    private void stamp(EvidenceEntity evidence, QuarantineEntry entry) {
        Map<String, Object> metadata = evidence.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(evidence.getMetadata());
        metadata.put("docproc.quarantine.reason", entry.reason().name());
        metadata.put("docproc.quarantine.detail", abbreviate(entry.detail()));
        metadata.put("docproc.quarantine.at", entry.at().toString());
        evidence.setMetadata(metadata);
        // Extraction failure is not an integrity verdict, so integrityOk is left alone unless the
        // hash itself is what failed.
        if (entry.reason() == Reason.HASH_MISMATCH) {
            evidence.setIntegrityOk(Boolean.FALSE);
            evidence.setIntegrityVerifiedAt(entry.at());
        }
        evidenceRepository.save(evidence);
    }

    private void remember(QuarantineEntry entry) {
        synchronized (history) {
            history.addFirst(entry);
            while (history.size() > historySize) {
                history.removeLast();
            }
        }
    }

    /** Most recent entries, newest first. */
    public List<QuarantineEntry> recent(int limit) {
        synchronized (history) {
            return history.stream().limit(Math.max(0, limit)).toList();
        }
    }

    public int size() {
        synchronized (history) {
            return history.size();
        }
    }

    private static String abbreviate(String detail) {
        return detail.length() > 500 ? detail.substring(0, 500) : detail;
    }
}
