package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.core.model.CaseView;
import com.laserpay.pdei.core.model.DisputeView;
import com.laserpay.pdei.core.model.FunnelMetrics;
import com.laserpay.pdei.core.model.PackageManifest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read/write port for the dispute side of the schema: {@code disputes}, {@code dispute_cases},
 * {@code case_evidence}, {@code investigations}, {@code investigation_findings},
 * {@code ai_admission_log}.
 */
public interface CaseRepositoryPort {

    // --- disputes -------------------------------------------------------------------------------

    void insertDispute(DisputeView dispute);

    Optional<DisputeView> findDispute(String disputeId);

    Optional<DisputeView> findOpenDisputeForTransaction(String transactionId);

    List<DisputeView> findDisputes(String merchantId, DisputeStatus status, DisputeReasonCode reasonCode,
                                   int page, int size);

    boolean updateDisputeStatus(String disputeId, DisputeStatus status, Instant at, Instant closedAt);

    /** Win rate over closed disputes, used for {@code historicalContext.merchantWinRate}. */
    double merchantWinRate(String merchantId);

    /** Count of closed disputes with the same reason code, for {@code historicalContext.similarCases}. */
    int similarCaseCount(String merchantId, DisputeReasonCode reasonCode);

    // --- cases ----------------------------------------------------------------------------------

    Optional<CaseView> findCase(String caseId);

    Optional<CaseView> findCaseByDispute(String disputeId);

    List<CaseView> findCases(String merchantId, CaseStatus status, int page, int size);

    boolean updateCaseStatus(String caseId, CaseStatus status, Instant at);

    void updateCasePackage(String caseId, int packageVersion, String manifestJson, Instant at);

    Optional<PackageManifest> findLatestManifest(String caseId);

    // --- case evidence --------------------------------------------------------------------------

    /** Replace the selected evidence set for a case (assembly is idempotent and re-runnable). */
    void replaceCaseEvidence(String caseId, List<CaseEvidenceRecord> evidence);

    List<CaseEvidenceRecord> findCaseEvidence(String caseId);

    // --- investigations -------------------------------------------------------------------------

    void saveInvestigation(InvestigationRecord investigation);

    Optional<InvestigationRecord> findInvestigation(String investigationId);

    Optional<InvestigationRecord> findLatestInvestigationForCase(String caseId);

    void appendAdmissionLog(AdmissionLogRecord record);

    /** Aggregated funnel counters for {@code GET /api/v1/metrics/funnel}. */
    FunnelMetrics funnel(String merchantId, Instant from, Instant to);
}
