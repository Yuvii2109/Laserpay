package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;

import java.util.List;

/**
 * Result of activity 3, {@code detectGaps}.
 *
 * <p>{@link #hasBlockingGaps()} is what decides whether step 4 waits at all. A gap only blocks when
 * it is HIGH or CRITICAL, or when it leaves a MANDATORY requirement unsatisfied: waiting seven days
 * for an OPTIONAL document nobody needs would be a bug, not caution.</p>
 */
public record GapReport(
        String caseId,
        String transactionId,
        List<Gap> gaps,
        int gapCount,
        int blockingGapCount,
        int contradictionCount,
        int unsatisfiedMandatoryCount,
        List<EvidenceType> missingMandatoryTypes) {

    public GapReport {
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        missingMandatoryTypes = missingMandatoryTypes == null
                ? List.of() : List.copyOf(missingMandatoryTypes);
    }

    /** One gap, flattened to the fields the workflow and the UI actually use. */
    public record Gap(
            String gapId,
            GapType type,
            EvidenceType evidenceType,
            GapSeverity severity,
            String detail) {
    }

    /** True when waiting for more evidence could still change the outcome. */
    public boolean hasBlockingGaps() {
        return blockingGapCount > 0 || unsatisfiedMandatoryCount > 0;
    }

    public static GapReport empty(String caseId, String transactionId) {
        return new GapReport(caseId, transactionId, List.of(), 0, 0, 0, 0, List.of());
    }
}
