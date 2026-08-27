package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.TimeWindows;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.policy.RequirementSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns "what we have" plus "what the policy demands" into a list of concrete, actionable gaps.
 *
 * <p>Gap ids are deterministic (a hash of transaction + type + evidence type + evidence id), so
 * recomputing readiness for an unchanged transaction produces the same gap ids and the persistence
 * layer can upsert rather than accumulate duplicates. This is what makes recomputation safe to run
 * on every inbound event.</p>
 *
 * <p>Gap types produced here map one to one onto {@code GapType}:</p>
 * <ul>
 *   <li>{@code MISSING} - a required evidence type has no satisfying artifact at all</li>
 *   <li>{@code EXPIRED} - an artifact of a required type exists but is past its expiry</li>
 *   <li>{@code EXPIRING_SOON} - expiry falls inside the window (7 days by default)</li>
 *   <li>{@code UNVERIFIABLE_PROVENANCE} - no source event, source or hash to prove where it came from</li>
 *   <li>{@code LOW_QUALITY} - extraction/quality score below the floor</li>
 *   <li>{@code VERSION_CONFLICT} - two live versions of the same artifact chain, or a live parent
 *       that should have been superseded</li>
 *   <li>{@code CONTRADICTORY} - one per contradiction found by {@link ContradictionDetector}</li>
 * </ul>
 */
public class GapDetector {

    /** Contract 7: EXPIRING_SOON means expiry within 7 days. */
    public static final int DEFAULT_EXPIRING_SOON_DAYS = 7;
    public static final double DEFAULT_LOW_QUALITY_THRESHOLD = 0.5d;

    private final int expiringSoonDays;
    private final double lowQualityThreshold;

    public GapDetector() {
        this(DEFAULT_EXPIRING_SOON_DAYS, DEFAULT_LOW_QUALITY_THRESHOLD);
    }

    public GapDetector(int expiringSoonDays, double lowQualityThreshold) {
        this.expiringSoonDays = expiringSoonDays <= 0 ? DEFAULT_EXPIRING_SOON_DAYS : expiringSoonDays;
        this.lowQualityThreshold = lowQualityThreshold;
    }

    public int expiringSoonDays() {
        return expiringSoonDays;
    }

    public List<ReadinessGap> detect(String transactionId, List<RequirementSpec> requirements,
                                     List<EvidenceView> evidence, List<ContradictionView> contradictions,
                                     Instant now) {
        List<ReadinessGap> gaps = new ArrayList<>();
        List<RequirementSpec> specs = requirements == null ? List.of() : requirements;
        List<EvidenceView> artifacts = evidence == null ? List.of() : evidence;

        for (RequirementSpec spec : specs) {
            if (spec.isProhibited()) {
                continue;
            }
            detectForRequirement(transactionId, spec, artifacts, now, gaps);
        }
        detectVersionConflicts(transactionId, artifacts, specs, now, gaps);

        if (contradictions != null) {
            for (ContradictionView contradiction : contradictions) {
                gaps.add(new ReadinessGap(
                        gapId(transactionId, GapType.CONTRADICTORY, null,
                                contradiction.left() + ">" + contradiction.right() + ">" + contradiction.field()),
                        transactionId, GapType.CONTRADICTORY, null,
                        contradiction.severity() == null ? GapSeverity.HIGH : contradiction.severity(),
                        contradiction.left(), contradiction.detail(), now, null));
            }
        }
        return List.copyOf(gaps);
    }

    private void detectForRequirement(String transactionId, RequirementSpec spec,
                                      List<EvidenceView> artifacts, Instant now, List<ReadinessGap> gaps) {
        List<EvidenceView> ofType = RequirementMatching.ofType(spec, artifacts);
        boolean satisfied = RequirementMatching.isSatisfied(spec, artifacts, now);

        if (!satisfied && ofType.isEmpty()) {
            gaps.add(new ReadinessGap(
                    gapId(transactionId, GapType.MISSING, spec.type(), null),
                    transactionId, GapType.MISSING, spec.type(), severityOf(spec.strength(), GapType.MISSING),
                    null,
                    "no " + spec.type() + " evidence is attached to this transaction",
                    now, null));
            return;
        }

        for (EvidenceView view : ofType) {
            if (view.isSuperseded()) {
                continue;
            }
            if (view.isExpiredAt(now)) {
                gaps.add(new ReadinessGap(
                        gapId(transactionId, GapType.EXPIRED, spec.type(), view.evidenceId()),
                        transactionId, GapType.EXPIRED, spec.type(),
                        severityOf(spec.strength(), GapType.EXPIRED), view.evidenceId(),
                        "evidence " + view.evidenceId() + " of type " + spec.type() + " has expired",
                        now, view.expiresAt()));
            } else if (view.expiresAt() != null
                    && TimeWindows.withinDays(now, view.expiresAt(), expiringSoonDays)) {
                gaps.add(new ReadinessGap(
                        gapId(transactionId, GapType.EXPIRING_SOON, spec.type(), view.evidenceId()),
                        transactionId, GapType.EXPIRING_SOON, spec.type(),
                        severityOf(spec.strength(), GapType.EXPIRING_SOON), view.evidenceId(),
                        "evidence " + view.evidenceId() + " of type " + spec.type() + " expires within "
                                + expiringSoonDays + " days",
                        now, view.expiresAt()));
            }

            if (!view.hasVerifiableProvenance()) {
                gaps.add(new ReadinessGap(
                        gapId(transactionId, GapType.UNVERIFIABLE_PROVENANCE, spec.type(), view.evidenceId()),
                        transactionId, GapType.UNVERIFIABLE_PROVENANCE, spec.type(),
                        severityOf(spec.strength(), GapType.UNVERIFIABLE_PROVENANCE), view.evidenceId(),
                        "evidence " + view.evidenceId() + " cannot be traced to a source event or hash",
                        now, null));
            }

            double floor = Math.max(spec.minQualityScore(), lowQualityThreshold);
            if (view.qualityScore() > 0.0d && view.qualityScore() < floor) {
                gaps.add(new ReadinessGap(
                        gapId(transactionId, GapType.LOW_QUALITY, spec.type(), view.evidenceId()),
                        transactionId, GapType.LOW_QUALITY, spec.type(),
                        severityOf(spec.strength(), GapType.LOW_QUALITY), view.evidenceId(),
                        "evidence " + view.evidenceId() + " has quality score " + view.qualityScore()
                                + ", below the floor of " + floor,
                        now, null));
            }
        }

        if (!satisfied && !ofType.isEmpty()) {
            boolean alreadyExplained = gaps.stream()
                    .anyMatch(gap -> gap.evidenceType() == spec.type() && gap.type() != GapType.MISSING);
            if (!alreadyExplained) {
                gaps.add(new ReadinessGap(
                        gapId(transactionId, GapType.MISSING, spec.type(), null),
                        transactionId, GapType.MISSING, spec.type(),
                        severityOf(spec.strength(), GapType.MISSING), null,
                        "no usable " + spec.type() + " evidence: every candidate is superseded,"
                                + " invalidated or still pending",
                        now, null));
            }
        }
    }

    /**
     * A version chain must have exactly one live head. Two live versions of the same artifact, or a
     * parent that is still ACTIVE after a child superseded it, means a submission could carry two
     * mutually inconsistent copies of the same document.
     */
    private void detectVersionConflicts(String transactionId, List<EvidenceView> artifacts,
                                        List<RequirementSpec> specs, Instant now, List<ReadinessGap> gaps) {
        Map<String, EvidenceView> byId = new LinkedHashMap<>();
        artifacts.forEach(view -> byId.put(view.evidenceId(), view));

        Map<String, List<EvidenceView>> liveByChain = new LinkedHashMap<>();
        for (EvidenceView view : artifacts) {
            if (!view.isUsable()) {
                continue;
            }
            liveByChain.computeIfAbsent(chainRoot(view, byId), key -> new ArrayList<>()).add(view);
        }

        for (Map.Entry<String, List<EvidenceView>> entry : liveByChain.entrySet()) {
            List<EvidenceView> live = entry.getValue();
            if (live.size() <= 1) {
                continue;
            }
            EvidenceView head = live.get(0);
            String ids = live.stream().map(EvidenceView::evidenceId).sorted().reduce((a, b) -> a + "," + b)
                    .orElse(head.evidenceId());
            RequirementStrength strength = strengthFor(head.type(), specs);
            gaps.add(new ReadinessGap(
                    gapId(transactionId, GapType.VERSION_CONFLICT, head.type(), entry.getKey()),
                    transactionId, GapType.VERSION_CONFLICT, head.type(),
                    severityOf(strength, GapType.VERSION_CONFLICT), head.evidenceId(),
                    "version chain " + entry.getKey() + " has " + live.size()
                            + " live versions (" + ids + "); exactly one must be current",
                    now, null));
        }
    }

    /** Walk parent links to the root of a version chain, tolerating a missing parent row. */
    private static String chainRoot(EvidenceView view, Map<String, EvidenceView> byId) {
        EvidenceView current = view;
        int guard = 0;
        while (current.parentEvidenceId() != null && guard++ < 64) {
            EvidenceView parent = byId.get(current.parentEvidenceId());
            if (parent == null) {
                return current.parentEvidenceId();
            }
            current = parent;
        }
        return current.evidenceId();
    }

    private static RequirementStrength strengthFor(EvidenceType type, List<RequirementSpec> specs) {
        return specs.stream()
                .filter(spec -> spec.type() == type)
                .map(RequirementSpec::strength)
                .findFirst()
                .orElse(RequirementStrength.OPTIONAL);
    }

    /**
     * Severity ladder. Anything blocking a MANDATORY requirement is at least HIGH; unverifiable
     * provenance on a mandatory artifact is CRITICAL because it cannot be fixed by waiting - the
     * artifact has to be re-captured from a source that can be proven.
     */
    static GapSeverity severityOf(RequirementStrength strength, GapType type) {
        boolean mandatory = strength == RequirementStrength.MANDATORY;
        boolean recommended = strength == RequirementStrength.RECOMMENDED;
        return switch (type) {
            case UNVERIFIABLE_PROVENANCE -> mandatory ? GapSeverity.CRITICAL
                    : recommended ? GapSeverity.MEDIUM : GapSeverity.LOW;
            case MISSING, EXPIRED -> mandatory ? GapSeverity.HIGH
                    : recommended ? GapSeverity.MEDIUM : GapSeverity.LOW;
            case EXPIRING_SOON -> mandatory ? GapSeverity.MEDIUM : GapSeverity.LOW;
            case VERSION_CONFLICT -> mandatory ? GapSeverity.HIGH : GapSeverity.MEDIUM;
            case LOW_QUALITY -> mandatory ? GapSeverity.MEDIUM : GapSeverity.LOW;
            case CONTRADICTORY -> GapSeverity.HIGH;
        };
    }

    /**
     * Deterministic gap id: the same gap on the same transaction always gets the same id, so
     * recomputation is idempotent.
     */
    static String gapId(String transactionId, GapType type, EvidenceType evidenceType, String discriminator) {
        String seed = String.join("|",
                transactionId == null ? "" : transactionId,
                type == null ? "" : type.name(),
                evidenceType == null ? "" : evidenceType.name(),
                discriminator == null ? "" : discriminator);
        return "GAP-" + Hashes.sha256Hex(seed).substring(0, 16).toUpperCase(java.util.Locale.ROOT);
    }
}
