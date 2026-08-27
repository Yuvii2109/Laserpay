package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.model.RequirementView;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.policy.RequirementSpec;
import com.laserpay.pdei.core.util.CoreErrors;
import com.laserpay.pdei.core.util.Scores;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The deterministic readiness scorer (platform contract 7).
 *
 * <pre>
 * base = 100 * (SUM weight(satisfied mandatory) + 0.5 * SUM weight(satisfied recommended))
 *            / (SUM weight(all mandatory)       + 0.5 * SUM weight(all recommended))
 * penalties:
 *   -15 per CONTRADICTORY gap
 *   -10 per EXPIRED mandatory evidence
 *   -5  per EXPIRING_SOON mandatory evidence (expiry within 7 days)
 *   -20 if any UNVERIFIABLE_PROVENANCE on mandatory evidence
 * score = clamp(round_half_up(base - penalties), 0, 100)
 * </pre>
 *
 * <p>Weights come from {@code RequirementStrength.weight()} (MANDATORY=3, RECOMMENDED=2,
 * OPTIONAL=1, PROHIBITED=0) unless the policy overrides them. OPTIONAL and PROHIBITED requirements
 * contribute to neither side of the ratio: they are reported in the snapshot for the UI but they
 * cannot move the score.</p>
 *
 * <p>When the denominator is zero (a policy with no mandatory or recommended requirements) the base
 * is 100: there is nothing outstanding to be unready about. Penalties still apply.</p>
 *
 * <p>{@link #score(ReadinessInput)} is a pure function - same input, same output, no clock, no I/O -
 * which is what makes the number reproducible and testable. {@link #compute(String, DisputeReasonCode)}
 * is the thin shell that gathers the input through {@link ReadinessDataProvider}.</p>
 */
public class ReadinessEngine {

    public static final int PENALTY_PER_CONTRADICTION = 15;
    public static final int PENALTY_PER_EXPIRED_MANDATORY = 10;
    public static final int PENALTY_PER_EXPIRING_SOON_MANDATORY = 5;
    public static final int PENALTY_UNVERIFIABLE_PROVENANCE_MANDATORY = 20;

    private static final String SNAPSHOT_PREFIX = "RDY-";
    private static final String METRIC_COMPUTATION = "pdei_readiness_computation_seconds";
    private static final String METRIC_SCORE = "pdei_readiness_score";

    private final ReadinessDataProvider provider;
    private final GapDetector gapDetector;
    private final ContradictionDetector contradictionDetector;
    private final Clocks clock;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicInteger> merchantScores = new ConcurrentHashMap<>();

    public ReadinessEngine(ReadinessDataProvider provider, GapDetector gapDetector,
                           ContradictionDetector contradictionDetector, Clocks clock,
                           MeterRegistry meterRegistry) {
        this.provider = provider;
        this.gapDetector = gapDetector == null ? new GapDetector() : gapDetector;
        this.contradictionDetector = contradictionDetector == null
                ? new ContradictionDetector() : contradictionDetector;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /** Readiness against the merchant baseline profile (no dispute raised yet). */
    public ReadinessSnapshot compute(String transactionId) {
        return compute(transactionId, null);
    }

    /**
     * Readiness of a transaction, optionally against a specific dispute reason code.
     *
     * @param reasonCode the reason code to score against, or null for the merchant baseline profile
     */
    public ReadinessSnapshot compute(String transactionId, DisputeReasonCode reasonCode) {
        CoreErrors.requireText(transactionId, "transactionId");
        long startNanos = System.nanoTime();
        Instant now = clock.now();

        String merchantId = provider.merchantIdFor(transactionId)
                .orElseThrow(() -> CoreErrors.notFound("transaction", transactionId));
        List<EvidenceView> evidence = provider.evidenceFor(transactionId);
        PolicyView policy = provider.policyFor(merchantId, reasonCode);
        TransactionFacts facts = provider.factsFor(transactionId)
                .orElseGet(() -> TransactionFacts.empty(transactionId, merchantId));

        List<ContradictionView> contradictions = contradictionDetector.detect(facts, evidence, now);
        List<ReadinessGap> gaps = gapDetector.detect(transactionId, policy.requirements(), evidence,
                contradictions, now);

        ReadinessSnapshot snapshot = score(new ReadinessInput(transactionId, merchantId, reasonCode,
                policy.requirements(), evidence, gaps, contradictions, policy.policyVersionId(), now));

        recordMetrics(merchantId, snapshot, startNanos);
        return snapshot;
    }

    /**
     * The formula itself. Pure: no clock, no I/O, no randomness.
     *
     * <p>Kept static on purpose so tests, the simulator and any future re-scoring job all run the
     * identical arithmetic.</p>
     */
    public static ReadinessSnapshot score(ReadinessInput input) {
        CoreErrors.requireValue(input, "input");
        Instant now = input.now();

        List<RequirementView> requirementViews = new ArrayList<>();
        double satisfiedWeight = 0.0d;
        double totalWeight = 0.0d;

        for (RequirementSpec spec : input.requirements()) {
            List<EvidenceView> satisfying = RequirementMatching.satisfying(spec, input.evidence(), now);
            boolean satisfied = !satisfying.isEmpty();
            int weight = spec.effectiveWeight();
            double contribution = contributionFactor(spec.strength());

            if (contribution > 0.0d) {
                totalWeight += weight * contribution;
                if (satisfied) {
                    satisfiedWeight += weight * contribution;
                }
            }

            requirementViews.add(new RequirementView(
                    spec.type(),
                    spec.strength(),
                    satisfied,
                    satisfying.stream().map(EvidenceView::evidenceId).toList(),
                    weight,
                    noteFor(spec, satisfied, input.evidence())));
        }

        double base = totalWeight <= 0.0d ? 100.0d : 100.0d * (satisfiedWeight / totalWeight);
        int penalties = penaltyPoints(input.gaps(), mandatoryTypes(input.requirements()));
        int finalScore = Scores.roundAndClamp(base - penalties, 0, 100);

        return new ReadinessSnapshot(
                Ids.withPrefix(SNAPSHOT_PREFIX),
                input.transactionId(),
                input.merchantId(),
                input.reasonCode(),
                finalScore,
                ReadinessBand.fromScore(finalScore),
                base,
                penalties,
                requirementViews,
                input.gaps(),
                input.contradictions(),
                input.policyVersionId(),
                now);
    }

    /**
     * Weighting of a requirement strength inside the ratio: MANDATORY counts fully, RECOMMENDED at
     * half, OPTIONAL and PROHIBITED not at all.
     */
    private static double contributionFactor(RequirementStrength strength) {
        if (strength == RequirementStrength.MANDATORY) {
            return 1.0d;
        }
        if (strength == RequirementStrength.RECOMMENDED) {
            return 0.5d;
        }
        return 0.0d;
    }

    /**
     * The four penalty rules of contract 7. Penalties for EXPIRED, EXPIRING_SOON and
     * UNVERIFIABLE_PROVENANCE only apply to gaps sitting on a MANDATORY evidence type; contradiction
     * penalties always apply, because a contradiction damages the whole package regardless of which
     * document carries it.
     */
    public static int penaltyPoints(List<ReadinessGap> gaps, Set<EvidenceType> mandatoryTypes) {
        if (gaps == null || gaps.isEmpty()) {
            return 0;
        }
        int contradictions = 0;
        int expiredMandatory = 0;
        int expiringSoonMandatory = 0;
        boolean unverifiableMandatory = false;

        for (ReadinessGap gap : gaps) {
            GapType type = gap.type();
            if (type == GapType.CONTRADICTORY) {
                contradictions++;
                continue;
            }
            boolean onMandatory = gap.evidenceType() != null && mandatoryTypes.contains(gap.evidenceType());
            if (!onMandatory) {
                continue;
            }
            switch (type) {
                case EXPIRED -> expiredMandatory++;
                case EXPIRING_SOON -> expiringSoonMandatory++;
                case UNVERIFIABLE_PROVENANCE -> unverifiableMandatory = true;
                default -> {
                    // MISSING, LOW_QUALITY and VERSION_CONFLICT are already priced into the base
                    // ratio through requirement satisfaction; double counting them would punish the
                    // same fact twice.
                }
            }
        }

        return contradictions * PENALTY_PER_CONTRADICTION
                + expiredMandatory * PENALTY_PER_EXPIRED_MANDATORY
                + expiringSoonMandatory * PENALTY_PER_EXPIRING_SOON_MANDATORY
                + (unverifiableMandatory ? PENALTY_UNVERIFIABLE_PROVENANCE_MANDATORY : 0);
    }

    public static Set<EvidenceType> mandatoryTypes(List<RequirementSpec> requirements) {
        Set<EvidenceType> types = EnumSet.noneOf(EvidenceType.class);
        if (requirements != null) {
            requirements.stream()
                    .filter(RequirementSpec::isMandatory)
                    .map(RequirementSpec::type)
                    .filter(java.util.Objects::nonNull)
                    .forEach(types::add);
        }
        return types;
    }

    /** Short human-readable explanation attached to each requirement row in the snapshot. */
    private static String noteFor(RequirementSpec spec, boolean satisfied, List<EvidenceView> evidence) {
        if (spec.isProhibited()) {
            boolean present = evidence.stream().anyMatch(view -> view.type() == spec.type() && view.isUsable());
            return present
                    ? "PROHIBITED evidence type is attached and must be removed before submission"
                    : "PROHIBITED evidence type, correctly absent";
        }
        if (satisfied) {
            return null;
        }
        boolean anyOfType = evidence.stream().anyMatch(view -> view.type() == spec.type());
        return anyOfType
                ? "present but not usable (superseded, expired, unverifiable or below quality floor)"
                : "not attached";
    }

    private void recordMetrics(String merchantId, ReadinessSnapshot snapshot, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Timer.builder(METRIC_COMPUTATION)
                    .register(meterRegistry)
                    .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            merchantScores.computeIfAbsent(merchantId, key -> {
                AtomicInteger holder = new AtomicInteger();
                meterRegistry.gauge(METRIC_SCORE, List.of(io.micrometer.core.instrument.Tag.of("merchant", key)),
                        holder, AtomicInteger::doubleValue);
                return holder;
            }).set(snapshot.score());
        } catch (RuntimeException e) {
            // Metrics must never break a financial computation.
        }
    }
}
