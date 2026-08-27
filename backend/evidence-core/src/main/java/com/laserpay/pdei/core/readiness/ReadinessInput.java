package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.policy.RequirementSpec;

import java.time.Instant;
import java.util.List;

/**
 * Everything {@link ReadinessEngine#score} needs, and nothing else.
 *
 * <p>The engine is a pure function of this record, which is why the scoring formula of platform
 * contract 7 can be unit tested exhaustively with no database, no Redis and no Spring context.</p>
 */
public record ReadinessInput(
        String transactionId,
        String merchantId,
        DisputeReasonCode reasonCode,
        List<RequirementSpec> requirements,
        List<EvidenceView> evidence,
        List<ReadinessGap> gaps,
        List<ContradictionView> contradictions,
        String policyVersionId,
        Instant now) {

    public ReadinessInput {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
    }
}
