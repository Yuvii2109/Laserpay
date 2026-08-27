package com.laserpay.pdei.core.dispute;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RequirementStrength;
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
import com.laserpay.pdei.core.evidence.EvidenceIntegrityService;
import com.laserpay.pdei.core.evidence.EvidenceGraphService;
import com.laserpay.pdei.core.evidence.IntegrityReport;
import com.laserpay.pdei.core.model.CaseView;
import com.laserpay.pdei.core.model.CaseXRay;
import com.laserpay.pdei.core.model.DisputeView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.PackageManifest;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.policy.RequirementSpec;
import com.laserpay.pdei.core.readiness.ReadinessEngine;
import com.laserpay.pdei.core.spi.CaseEvidenceRecord;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.InvestigationRecord;
import com.laserpay.pdei.core.storage.Buckets;
import com.laserpay.pdei.core.storage.ObjectStore;
import com.laserpay.pdei.core.storage.StoredObject;
import com.laserpay.pdei.core.timeline.TimelineService;
import com.laserpay.pdei.core.util.CoreErrors;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns a case into a submittable representment package.
 *
 * <p>Three steps, in this order, because each depends on the previous being honest:</p>
 * <ol>
 *   <li><b>Select</b> - take the usable evidence for the transaction, drop prohibited types, keep one
 *       live version per chain, and order it by requirement strength so the strongest documents lead.</li>
 *   <li><b>Verify</b> - re-hash every selected object. An artifact that fails integrity is excluded
 *       from the bundle rather than submitted; submitting bytes we cannot vouch for is worse than
 *       submitting fewer of them.</li>
 *   <li><b>Bundle</b> - write a zip plus a {@code manifest.json} to {@code pdei-packages} under the
 *       key layout of platform contract 11, recording the sha256 of every entry and of the zip.</li>
 * </ol>
 *
 * <p>Assembly is re-runnable: the case evidence set is replaced, and each run writes a new package
 * version rather than overwriting the last one.</p>
 */
public class CaseAssemblyService {

    private static final Logger log = LoggerFactory.getLogger(CaseAssemblyService.class);
    private static final String ENTITY_TYPE = "CASE";
    private static final String METRIC_ASSEMBLY = "pdei_case_assembly_seconds";
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String EVIDENCE_PREFIX = "evidence/";

    private final CaseRepositoryPort cases;
    private final EvidenceRepositoryPort evidence;
    private final ObjectStore objectStore;
    private final ReadinessEngine readinessEngine;
    private final PolicyEngine policyEngine;
    private final EvidenceIntegrityService integrityService;
    private final EvidenceGraphService graphService;
    private final TimelineService timelineService;
    private final AuditRecorder audit;
    private final EventPublisherPort publisher;
    private final Clocks clock;
    private final MeterRegistry meterRegistry;

    public CaseAssemblyService(CaseRepositoryPort cases, EvidenceRepositoryPort evidence,
                               ObjectStore objectStore, ReadinessEngine readinessEngine,
                               PolicyEngine policyEngine, EvidenceIntegrityService integrityService,
                               EvidenceGraphService graphService, TimelineService timelineService,
                               AuditRecorder audit, EventPublisherPort publisher, Clocks clock,
                               MeterRegistry meterRegistry) {
        this.cases = cases;
        this.evidence = evidence;
        this.objectStore = objectStore;
        this.readinessEngine = readinessEngine;
        this.policyEngine = policyEngine;
        this.integrityService = integrityService;
        this.graphService = graphService;
        this.timelineService = timelineService;
        this.audit = audit;
        this.publisher = publisher;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Select the evidence that should go into a case.
     *
     * <p>Ordering matters for the reviewer and for the network: mandatory documents first, in the
     * order the policy lists them, then recommended, then optional, then anything else attached to
     * the transaction.</p>
     */
    public List<EvidenceView> selectEvidence(String transactionId, PolicyView policy) {
        List<EvidenceView> usable = evidence.findByTransactionIdAndStatusIn(transactionId, EvidenceView.USABLE);
        Map<EvidenceType, Integer> rank = new LinkedHashMap<>();
        int index = 0;
        for (RequirementSpec spec : policy.requirements()) {
            if (!spec.isProhibited()) {
                rank.putIfAbsent(spec.type(), index++);
            }
        }

        // One live artifact per type; when a type has several, the newest version wins.
        Map<EvidenceType, EvidenceView> best = new LinkedHashMap<>();
        for (EvidenceView view : usable) {
            if (policy.isProhibited(view.type())) {
                continue;
            }
            best.merge(view.type(), view, (left, right) -> {
                if (right.version() != left.version()) {
                    return right.version() > left.version() ? right : left;
                }
                Instant leftAt = left.createdAt() == null ? Instant.EPOCH : left.createdAt();
                Instant rightAt = right.createdAt() == null ? Instant.EPOCH : right.createdAt();
                return rightAt.isAfter(leftAt) ? right : left;
            });
        }

        return best.values().stream()
                .sorted(Comparator
                        .<EvidenceView>comparingInt(view -> strengthOrder(policy.strengthOf(view.type())))
                        .thenComparingInt(view -> rank.getOrDefault(view.type(), Integer.MAX_VALUE))
                        .thenComparing(EvidenceView::evidenceId))
                .toList();
    }

    /**
     * Assemble and store the representment package. Returns the manifest that was written next to
     * the bundle.
     */
    public PackageManifest assemble(String caseId, String actor) {
        long startNanos = System.nanoTime();
        CaseView caseView = cases.findCase(CoreErrors.requireText(caseId, "caseId"))
                .orElseThrow(() -> CoreErrors.notFound(ENTITY_TYPE, caseId));
        DisputeView dispute = cases.findDispute(caseView.disputeId())
                .orElseThrow(() -> CoreErrors.notFound("DISPUTE", caseView.disputeId()));

        PolicyView policy = policyEngine.applicablePolicy(caseView.merchantId(), dispute.reasonCode());
        ReadinessSnapshot readiness =
                readinessEngine.compute(caseView.transactionId(), dispute.reasonCode());

        List<EvidenceView> selected = new ArrayList<>();
        for (EvidenceView view : selectEvidence(caseView.transactionId(), policy)) {
            IntegrityReport report = integrityService.verify(view);
            if (report.intact()) {
                selected.add(view);
            } else {
                log.error("excluding evidence {} from case {}: integrity check failed ({})",
                        view.evidenceId(), caseId, report.detail());
            }
        }
        if (selected.isEmpty()) {
            throw CoreErrors.policyViolation("case " + caseId + " has no verifiable evidence to submit");
        }

        int packageVersion = Math.max(1, caseView.packageVersion() + 1);
        List<PackageManifest.Item> items = new ArrayList<>();
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        int position = 0;
        for (EvidenceView view : selected) {
            position++;
            String entryPath = EVIDENCE_PREFIX + String.format("%02d", position) + "-" + view.type() + "-"
                    + Buckets.safeFilename(view.filename());
            byte[] content = objectStore.getBytes(Buckets.EVIDENCE, view.objectKey());
            payloads.put(entryPath, content);
            items.add(new PackageManifest.Item(view.evidenceId(), view.type(),
                    policy.strengthOf(view.type()), view.version(), view.sha256(), view.objectKey(),
                    view.filename(), view.contentType(), view.sizeBytes(), entryPath, view.createdAt()));
        }

        String narrative = latestNarrative(caseId).orElse(null);
        PackageManifest draftManifest = new PackageManifest(
                Ids.withPrefix("PKG-"), caseId, dispute.disputeId(), caseView.merchantId(),
                caseView.transactionId(), dispute.reasonCode(), dispute.amount(), packageVersion,
                Buckets.packageBundleKey(caseView.merchantId(), caseId, packageVersion), null, 0L,
                items, narrative, policy.policyVersionId(), readiness.score(), readiness.band(),
                actor == null ? "SYSTEM" : actor, clock.now());

        byte[] manifestJson = Json.write(draftManifest).getBytes(StandardCharsets.UTF_8);
        byte[] bundle = zip(payloads, manifestJson);

        StoredObject storedBundle = objectStore.put(Buckets.PACKAGES, draftManifest.bundleObjectKey(),
                bundle, "application/zip",
                Map.of(Buckets.META_EVIDENCE_ID, caseId, Buckets.META_VERSION,
                        String.valueOf(packageVersion)));

        PackageManifest manifest = new PackageManifest(draftManifest.manifestId(), caseId,
                dispute.disputeId(), caseView.merchantId(), caseView.transactionId(), dispute.reasonCode(),
                dispute.amount(), packageVersion, storedBundle.objectKey(), storedBundle.sha256(),
                storedBundle.sizeBytes(), items, narrative, policy.policyVersionId(), readiness.score(),
                readiness.band(), draftManifest.generatedBy(), draftManifest.generatedAt());

        String manifestString = Json.write(manifest);
        objectStore.put(Buckets.PACKAGES, Buckets.packageManifestKey(caseView.merchantId(), caseId),
                manifestString.getBytes(StandardCharsets.UTF_8), "application/json",
                Map.of(Buckets.META_EVIDENCE_ID, caseId, Buckets.META_VERSION,
                        String.valueOf(packageVersion)));

        persistSelection(caseId, selected, policy);
        cases.updateCasePackage(caseId, packageVersion, manifestString, clock.now());
        cases.updateCaseStatus(caseId, CaseStatus.PREPARED, clock.now());

        audit.record(AuditCommand.of(ENTITY_TYPE, caseId, caseView.merchantId(), "CASE_PACKAGE_ASSEMBLED",
                        actor, ActorType.SYSTEM)
                .withAfter(manifest));
        publish(EventType.CaseEvidenceAttached, caseView, manifest);
        recordAssembly(startNanos);
        log.info("assembled package v{} for case {} with {} artifact(s), bundle sha256={}",
                packageVersion, caseId, items.size(), storedBundle.sha256());
        return manifest;
    }

    /**
     * The full Case X-Ray payload: readiness, evidence, graph, timeline, the AI proposal and the
     * deterministic verdict that was applied to it.
     */
    public CaseXRay xray(String caseId) {
        CaseView caseView = cases.findCase(CoreErrors.requireText(caseId, "caseId"))
                .orElseThrow(() -> CoreErrors.notFound(ENTITY_TYPE, caseId));
        DisputeView dispute = cases.findDispute(caseView.disputeId())
                .orElseThrow(() -> CoreErrors.notFound("DISPUTE", caseView.disputeId()));
        ReadinessSnapshot readiness =
                readinessEngine.compute(caseView.transactionId(), dispute.reasonCode());
        List<EvidenceView> artifacts = evidence.findByTransactionId(caseView.transactionId());
        Optional<InvestigationRecord> investigation = cases.findLatestInvestigationForCase(caseId);

        return new CaseXRay(
                caseId,
                dispute.disputeId(),
                caseView.transactionId(),
                caseView.merchantId(),
                caseView.status(),
                dispute.status(),
                dispute.reasonCode(),
                dispute.amount(),
                dispute.deadlineAt(),
                readiness,
                artifacts,
                graphService.build(caseView.transactionId()),
                timelineService.timeline(caseView.transactionId()),
                readiness.gaps(),
                readiness.contradictions(),
                investigation.map(InvestigationRecord::resultJson)
                        .map(json -> readOrNull(json, com.laserpay.pdei.core.model.InvestigationResult.class))
                        .orElse(null),
                investigation.map(InvestigationRecord::verdictJson)
                        .map(json -> readOrNull(json, com.laserpay.pdei.core.model.SafetyVerdict.class))
                        .orElse(null),
                cases.findLatestManifest(caseId).orElse(null),
                List.of(),
                clock.now());
    }

    /** Stored JSON is data, not code: a shape drift must not break the whole X-Ray screen. */
    private static <T> T readOrNull(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return Json.read(json, type);
        } catch (RuntimeException e) {
            log.warn("could not deserialise stored {}: {}", type.getSimpleName(), e.toString());
            return null;
        }
    }

    /** Persist the selected set so the package contents are reconstructable from the database alone. */
    private void persistSelection(String caseId, List<EvidenceView> selected, PolicyView policy) {
        List<CaseEvidenceRecord> records = new ArrayList<>();
        int position = 0;
        Instant now = clock.now();
        for (EvidenceView view : selected) {
            records.add(new CaseEvidenceRecord(caseId, view.evidenceId(), policy.strengthOf(view.type()),
                    ++position, view.sha256(), now));
        }
        cases.replaceCaseEvidence(caseId, records);
    }

    private Optional<String> latestNarrative(String caseId) {
        return cases.findLatestInvestigationForCase(caseId)
                .map(InvestigationRecord::narrative)
                .filter(narrative -> narrative != null && !narrative.isBlank());
    }

    /** Deterministic zip: entries are written in a fixed order so the bundle hash is reproducible. */
    private static byte[] zip(Map<String, byte[]> payloads, byte[] manifestJson) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            writeEntry(zip, MANIFEST_ENTRY, manifestJson);
            for (Map.Entry<String, byte[]> entry : payloads.entrySet()) {
                writeEntry(zip, entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            throw CoreErrors.upstream("MinIO", "could not build representment bundle: " + e);
        }
        return buffer.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content) throws java.io.IOException {
        ZipEntry entry = new ZipEntry(name);
        // Fixed timestamp keeps the bundle hash stable for identical content.
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static int strengthOrder(RequirementStrength strength) {
        if (strength == null) {
            return 3;
        }
        return switch (strength) {
            case MANDATORY -> 0;
            case RECOMMENDED -> 1;
            case OPTIONAL -> 2;
            case PROHIBITED -> 4;
        };
    }

    private void publish(EventType eventType, CaseView caseView, PackageManifest manifest) {
        CanonicalEvent event = new CanonicalEvent(
                Ids.eventId(), eventType, 1, AggregateType.CASE, caseView.caseId(), caseView.merchantId(),
                Ids.eventId(), null, clock.now(), clock.now(), EventSource.INTERNAL,
                eventType.name() + ":" + caseView.caseId() + ":v" + manifest.packageVersion(),
                Json.tree(manifest));
        publisher.publish(Topics.CASE_EVENTS, event);
    }

    private void recordAssembly(long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Timer.builder(METRIC_ASSEMBLY).register(meterRegistry)
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            // metrics never break assembly
        }
    }

    /** Bundle content hash, exposed so a submission can be proven byte-identical later. */
    public String bundleSha256(byte[] bundle) {
        return Hashes.sha256(bundle);
    }
}
