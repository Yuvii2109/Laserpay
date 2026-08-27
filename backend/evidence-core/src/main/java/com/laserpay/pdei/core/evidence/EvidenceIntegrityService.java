package com.laserpay.pdei.core.evidence;

import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.audit.AuditCommand;
import com.laserpay.pdei.core.audit.AuditRecorder;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.storage.Buckets;
import com.laserpay.pdei.core.storage.ObjectStat;
import com.laserpay.pdei.core.storage.ObjectStore;
import com.laserpay.pdei.core.util.CoreErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Proves that the bytes we would submit are the bytes we captured.
 *
 * <p>Re-reads the object from MinIO, hashes it and compares against the sha256 recorded in Postgres
 * at capture time. A mismatch - or a missing object - is treated as tampering: the artifact is moved
 * to INVALIDATED, {@code EvidenceInvalidated} is published so readiness recomputes without it, and
 * the finding is written to the audit chain.</p>
 *
 * <p>This is also the detector for the simulator's {@code CORRUPT_EVIDENCE_HASH} and
 * {@code DELETE_EVIDENCE} chaos injections.</p>
 */
public class EvidenceIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceIntegrityService.class);
    private static final String ENTITY_TYPE = "EVIDENCE";

    private final EvidenceRepositoryPort repository;
    private final ObjectStore objectStore;
    private final EvidenceService evidenceService;
    private final AuditRecorder audit;
    private final Clocks clock;

    public EvidenceIntegrityService(EvidenceRepositoryPort repository, ObjectStore objectStore,
                                    EvidenceService evidenceService, AuditRecorder audit, Clocks clock) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.evidenceService = evidenceService;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Verify one artifact. On failure the artifact is invalidated before this method returns, so a
     * caller cannot accidentally keep using a tampered document.
     */
    public IntegrityReport verify(String evidenceId) {
        EvidenceView view = repository.findById(evidenceId)
                .orElseThrow(() -> CoreErrors.notFound(ENTITY_TYPE, evidenceId));
        return verify(view);
    }

    public IntegrityReport verify(EvidenceView view) {
        Instant now = clock.now();
        String actual;
        try {
            byte[] content = objectStore.getBytes(Buckets.EVIDENCE, view.objectKey());
            actual = Hashes.sha256(content);
        } catch (RuntimeException e) {
            IntegrityReport report = IntegrityReport.missing(view.evidenceId(), view.objectKey(),
                    view.sha256(), "stored object could not be read: " + e, now);
            handleFailure(view, report);
            return report;
        }

        if (actual.equals(view.sha256())) {
            return IntegrityReport.ok(view.evidenceId(), view.objectKey(), actual, now);
        }
        IntegrityReport report = IntegrityReport.mismatch(view.evidenceId(), view.objectKey(),
                view.sha256(), actual, now);
        handleFailure(view, report);
        return report;
    }

    /** Verify every artifact on a transaction; returns only the failures. */
    public List<IntegrityReport> verifyTransaction(String transactionId) {
        List<IntegrityReport> failures = new ArrayList<>();
        for (EvidenceView view : repository.findByTransactionId(transactionId)) {
            IntegrityReport report = verify(view);
            if (!report.intact()) {
                failures.add(report);
            }
        }
        return List.copyOf(failures);
    }

    /**
     * Cross-check the hash MinIO itself recorded in user metadata against the database. Cheap
     * (metadata only, no payload transfer) so it can run over large sets; a full {@link #verify} is
     * still required to prove the bytes.
     */
    public boolean metadataMatches(EvidenceView view) {
        try {
            ObjectStat stat = objectStore.stat(Buckets.EVIDENCE, view.objectKey());
            String recorded = stat.recordedSha256();
            return recorded == null || recorded.equals(view.sha256());
        } catch (RuntimeException e) {
            log.warn("could not stat object for evidence {}: {}", view.evidenceId(), e.toString());
            return false;
        }
    }

    private void handleFailure(EvidenceView view, IntegrityReport report) {
        log.error("integrity failure on evidence {}: expected sha256={} actual={} ({})",
                view.evidenceId(), report.expectedSha256(), report.actualSha256(), report.detail());
        audit.record(AuditCommand.of(ENTITY_TYPE, view.evidenceId(), view.merchantId(),
                        "EVIDENCE_INTEGRITY_FAILED", "SYSTEM", ActorType.SYSTEM)
                .withBefore(view)
                .withAfter(report));
        evidenceService.invalidate(view.evidenceId(),
                report.objectMissing() ? "stored object missing or unreadable" : "sha256 mismatch",
                "SYSTEM");
    }
}
