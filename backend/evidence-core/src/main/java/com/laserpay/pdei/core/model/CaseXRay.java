package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;
import java.util.List;

/**
 * Everything the Case X-Ray screen needs in one payload
 * ({@code GET /api/v1/cases/{caseId}/xray}, frontend route {@code /cases/[caseId]}).
 *
 * <p>Deliberately includes both the AI proposal ({@code investigation}) and the deterministic
 * verdict that was applied to it ({@code safetyVerdict}) so a human can always see what the model
 * asked for and why the platform did or did not allow it.</p>
 */
public record CaseXRay(
        String caseId,
        String disputeId,
        String transactionId,
        String merchantId,
        CaseStatus caseStatus,
        DisputeStatus disputeStatus,
        DisputeReasonCode reasonCode,
        Money disputeAmount,
        Instant deadlineAt,
        ReadinessSnapshot readiness,
        List<EvidenceView> evidence,
        EvidenceGraph graph,
        List<TimelineEntry> timeline,
        List<ReadinessGap> gaps,
        List<ContradictionView> contradictions,
        InvestigationResult investigation,
        SafetyVerdict safetyVerdict,
        PackageManifest packageManifest,
        List<String> auditEventIds,
        Instant generatedAt) {

    public CaseXRay {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
        auditEventIds = auditEventIds == null ? List.of() : List.copyOf(auditEventIds);
    }
}
