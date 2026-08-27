package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.policy.RequirementSpec;

import java.time.Instant;
import java.util.List;

/**
 * The single definition of "this requirement is satisfied".
 *
 * <p>{@link ReadinessEngine} and {@link GapDetector} must agree exactly - a requirement counted as
 * satisfied by the score but reported as MISSING by the detector would make the whole readiness
 * number untrustworthy - so both call these methods and neither has its own copy of the rule.</p>
 */
public final class RequirementMatching {

    private RequirementMatching() {
    }

    /** All evidence of the requirement's type, whatever its status. */
    public static List<EvidenceView> ofType(RequirementSpec spec, List<EvidenceView> evidence) {
        if (spec == null || evidence == null) {
            return List.of();
        }
        return evidence.stream().filter(view -> view.type() == spec.type()).toList();
    }

    /**
     * Evidence that actually satisfies the requirement. An artifact satisfies when it is:
     * <ul>
     *   <li>of the required type;</li>
     *   <li>ACTIVE or EXPIRING (PENDING, EXPIRED, INVALIDATED and SUPERSEDED never satisfy);</li>
     *   <li>not past its expiry instant, even if a status event has not arrived yet;</li>
     *   <li>backed by verifiable provenance when the requirement demands it;</li>
     *   <li>at or above the requirement's minimum quality score.</li>
     * </ul>
     * A PROHIBITED requirement is never satisfied - its presence is a policy problem, not a credit.
     */
    public static List<EvidenceView> satisfying(RequirementSpec spec, List<EvidenceView> evidence, Instant now) {
        if (spec == null || spec.isProhibited()) {
            return List.of();
        }
        return ofType(spec, evidence).stream()
                .filter(EvidenceView::isUsable)
                .filter(view -> !view.isExpiredAt(now))
                .filter(view -> !spec.provenanceRequired() || view.hasVerifiableProvenance())
                .filter(view -> view.qualityScore() >= spec.minQualityScore())
                .toList();
    }

    public static boolean isSatisfied(RequirementSpec spec, List<EvidenceView> evidence, Instant now) {
        return !satisfying(spec, evidence, now).isEmpty();
    }
}
