package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.common.domain.RequirementStrength;

import java.time.Instant;

/** One row of {@code pdei.case_evidence}: an evidence artifact selected into a case. */
public record CaseEvidenceRecord(
        String caseId,
        String evidenceId,
        RequirementStrength strength,
        int position,
        String sha256AtSelection,
        Instant attachedAt) {
}
