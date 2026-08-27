package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;
import java.util.List;

/**
 * Answer of the {@code getCaseState} query: the complete in-memory state of a running case.
 *
 * <p>Queries never touch Postgres. This is the workflow's own view, which is exactly what makes it
 * useful: it is correct even when the read model lags behind, and it costs one query task rather
 * than a database round trip.</p>
 */
public record CaseState(
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        CaseStatus caseStatus,
        DisputeStatus disputeStatus,
        CasePhase phase,
        Money disputeAmount,
        Instant deadlineAt,

        int readinessScore,
        ReadinessBand readinessBand,
        int evidenceCount,
        int gapCount,
        int blockingGapCount,
        int contradictionCount,
        boolean allMandatorySatisfied,
        List<String> evidenceArrivedSinceStart,

        boolean aiAdmitted,
        int admissionPriority,
        String admissionShortCircuit,
        InvestigationClassification classification,
        double confidence,
        RecommendedAction recommendedAction,
        SafetyDecision safetyDecision,
        List<String> gateReasons,

        HumanDecisionType humanDecision,
        String humanDecisionActor,
        String humanDecisionNotes,

        int packageVersion,
        String bundleObjectKey,
        String bundleSha256,
        String networkReference,
        boolean submissionSimulated,

        int assessmentRound,
        int followUpTick,
        int continuationCount,
        boolean cancelled,
        String cancelReason,
        String failureReason,
        CaseResolution resolution) {

    public CaseState {
        evidenceArrivedSinceStart = evidenceArrivedSinceStart == null
                ? List.of() : List.copyOf(evidenceArrivedSinceStart);
        gateReasons = gateReasons == null ? List.of() : List.copyOf(gateReasons);
    }
}
