package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.core.TestFixtures;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.policy.RequirementSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoring table of platform contract 7.
 *
 * <pre>
 * base = 100 * (SUM weight(satisfied mandatory) + 0.5 * SUM weight(satisfied recommended))
 *            / (SUM weight(all mandatory)       + 0.5 * SUM weight(all recommended))
 * penalties: -15 contradiction, -10 expired mandatory, -5 expiring-soon mandatory,
 *            -20 once for any unverifiable provenance on mandatory
 * score = clamp(round_half_up(base - penalties), 0, 100)
 * </pre>
 */
class ReadinessEngineTest {

    private static final Instant NOW = TestFixtures.NOW;

    private static ReadinessSnapshot score(List<RequirementSpec> requirements, List<EvidenceView> evidence,
                                           List<ReadinessGap> gaps, List<ContradictionView> contradictions) {
        return ReadinessEngine.score(new ReadinessInput(TestFixtures.TRANSACTION, TestFixtures.MERCHANT,
                null, requirements, evidence, gaps, contradictions, "POL-TEST-V1", NOW));
    }

    private static ReadinessGap gap(GapType type, EvidenceType evidenceType) {
        return new ReadinessGap("GAP-" + type + "-" + evidenceType, TestFixtures.TRANSACTION, type,
                evidenceType, GapSeverity.HIGH, null, "detail", NOW, null);
    }

    @Test
    @DisplayName("all mandatory satisfied scores 100 and lands in READY")
    void allMandatorySatisfied() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF),
                        TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.DELIVERY_PROOF).build()),
                List.of(), List.of());

        assertThat(snapshot.baseScore()).isEqualTo(100.0d);
        assertThat(snapshot.penaltyPoints()).isZero();
        assertThat(snapshot.score()).isEqualTo(100);
        assertThat(snapshot.band()).isEqualTo(ReadinessBand.READY);
        assertThat(snapshot.allMandatorySatisfied()).isTrue();
    }

    @Test
    @DisplayName("half the mandatory weight satisfied scores 50 - the AT_RISK boundary")
    void halfMandatorySatisfied() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF),
                        TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build()),
                List.of(), List.of());

        assertThat(snapshot.score()).isEqualTo(50);
        assertThat(snapshot.band()).isEqualTo(ReadinessBand.AT_RISK);
        assertThat(snapshot.unsatisfiedMandatory()).hasSize(1);
    }

    @Test
    @DisplayName("recommended requirements count at half weight on both sides of the ratio")
    void recommendedCountsAtHalfWeight() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF),
                        TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF),
                        TestFixtures.recommended(EvidenceType.CUSTOMER_COMMUNICATION)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.DELIVERY_PROOF).build()),
                List.of(), List.of());

        // numerator 6, denominator 6 + 0.5*2 = 7 -> 85.714... -> 86
        assertThat(snapshot.score()).isEqualTo(86);
        assertThat(snapshot.band()).isEqualTo(ReadinessBand.NEARLY_READY);
    }

    @Test
    @DisplayName("a base of exactly 87.5 rounds half up to 88")
    void roundsHalfUp() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF),
                        TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF),
                        TestFixtures.recommended(EvidenceType.CUSTOMER_COMMUNICATION),
                        TestFixtures.recommended(EvidenceType.ORDER_RECORD)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.DELIVERY_PROOF).build(),
                        TestFixtures.evidence("EV-3", EvidenceType.ORDER_RECORD).build()),
                List.of(), List.of());

        // numerator 6 + 0.5*2 = 7, denominator 6 + 0.5*4 = 8 -> 87.5 -> 88
        assertThat(snapshot.baseScore()).isEqualTo(87.5d);
        assertThat(snapshot.score()).isEqualTo(88);
    }

    @Test
    @DisplayName("each CONTRADICTORY gap costs 15 points")
    void contradictionPenalty() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build()),
                List.of(gap(GapType.CONTRADICTORY, null)),
                List.of(ContradictionView.narrative("delivery precedes dispatch")));

        assertThat(snapshot.penaltyPoints()).isEqualTo(15);
        assertThat(snapshot.score()).isEqualTo(85);
        assertThat(snapshot.band()).isEqualTo(ReadinessBand.NEARLY_READY);
    }

    @Test
    @DisplayName("an EXPIRED mandatory artifact costs 10 points on top of failing its requirement")
    void expiredMandatoryPenalty() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF),
                        TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.DELIVERY_PROOF)
                                .status(EvidenceStatus.EXPIRED).build()),
                List.of(gap(GapType.EXPIRED, EvidenceType.DELIVERY_PROOF)),
                List.of());

        // base 50 (expired evidence does not satisfy) minus 10
        assertThat(snapshot.baseScore()).isEqualTo(50.0d);
        assertThat(snapshot.penaltyPoints()).isEqualTo(10);
        assertThat(snapshot.score()).isEqualTo(40);
        assertThat(snapshot.band()).isEqualTo(ReadinessBand.NOT_READY);
    }

    @Test
    @DisplayName("an EXPIRING_SOON mandatory artifact costs 5 points but still satisfies")
    void expiringSoonMandatoryPenalty() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF),
                        TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.DELIVERY_PROOF)
                                .status(EvidenceStatus.EXPIRING)
                                .expiresAt(NOW.plusSeconds(3 * 24 * 3600)).build()),
                List.of(gap(GapType.EXPIRING_SOON, EvidenceType.DELIVERY_PROOF)),
                List.of());

        assertThat(snapshot.baseScore()).isEqualTo(100.0d);
        assertThat(snapshot.penaltyPoints()).isEqualTo(5);
        assertThat(snapshot.score()).isEqualTo(95);
        assertThat(snapshot.band()).isEqualTo(ReadinessBand.READY);
    }

    @Test
    @DisplayName("UNVERIFIABLE_PROVENANCE on mandatory evidence costs 20 points exactly once")
    void unverifiableProvenancePenaltyAppliesOnce() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF),
                        TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.DELIVERY_PROOF).build()),
                List.of(gap(GapType.UNVERIFIABLE_PROVENANCE, EvidenceType.PAYMENT_PROOF),
                        gap(GapType.UNVERIFIABLE_PROVENANCE, EvidenceType.DELIVERY_PROOF)),
                List.of());

        assertThat(snapshot.penaltyPoints()).isEqualTo(20);
        assertThat(snapshot.score()).isEqualTo(80);
        assertThat(snapshot.band()).isEqualTo(ReadinessBand.NEARLY_READY);
    }

    @Test
    @DisplayName("gaps on non-mandatory evidence types do not trigger the mandatory penalties")
    void nonMandatoryGapsDoNotPenalise() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF),
                        TestFixtures.recommended(EvidenceType.CUSTOMER_COMMUNICATION)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.CUSTOMER_COMMUNICATION).build()),
                List.of(gap(GapType.EXPIRED, EvidenceType.CUSTOMER_COMMUNICATION),
                        gap(GapType.UNVERIFIABLE_PROVENANCE, EvidenceType.CUSTOMER_COMMUNICATION)),
                List.of());

        assertThat(snapshot.penaltyPoints()).isZero();
        assertThat(snapshot.score()).isEqualTo(100);
    }

    @Test
    @DisplayName("penalties are clamped at zero, never negative")
    void clampsAtZero() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF),
                        TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build()),
                List.of(gap(GapType.CONTRADICTORY, null), gap(GapType.CONTRADICTORY, null),
                        gap(GapType.CONTRADICTORY, null), gap(GapType.CONTRADICTORY, null)),
                List.of());

        assertThat(snapshot.penaltyPoints()).isEqualTo(60);
        assertThat(snapshot.score()).isZero();
        assertThat(snapshot.band()).isEqualTo(ReadinessBand.NOT_READY);
    }

    @Test
    @DisplayName("band boundaries: 90 is READY, 75 is NEARLY_READY, 50 is AT_RISK, 45 is NOT_READY")
    void bandBoundaries() {
        List<RequirementSpec> requirements = List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF));
        List<EvidenceView> evidence =
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build());

        ReadinessSnapshot ready = score(requirements, evidence,
                List.of(gap(GapType.EXPIRED, EvidenceType.PAYMENT_PROOF)), List.of());
        assertThat(ready.score()).isEqualTo(90);
        assertThat(ready.band()).isEqualTo(ReadinessBand.READY);

        ReadinessSnapshot nearly = score(requirements, evidence,
                List.of(gap(GapType.CONTRADICTORY, null), gap(GapType.EXPIRED, EvidenceType.PAYMENT_PROOF)),
                List.of());
        assertThat(nearly.score()).isEqualTo(75);
        assertThat(nearly.band()).isEqualTo(ReadinessBand.NEARLY_READY);

        ReadinessSnapshot atRisk = score(requirements, evidence,
                List.of(gap(GapType.CONTRADICTORY, null), gap(GapType.CONTRADICTORY, null),
                        gap(GapType.UNVERIFIABLE_PROVENANCE, EvidenceType.PAYMENT_PROOF)),
                List.of());
        assertThat(atRisk.score()).isEqualTo(50);
        assertThat(atRisk.band()).isEqualTo(ReadinessBand.AT_RISK);

        ReadinessSnapshot notReady = score(requirements, evidence,
                List.of(gap(GapType.CONTRADICTORY, null), gap(GapType.CONTRADICTORY, null),
                        gap(GapType.CONTRADICTORY, null),
                        gap(GapType.EXPIRED, EvidenceType.PAYMENT_PROOF)),
                List.of());
        assertThat(notReady.score()).isEqualTo(45);
        assertThat(notReady.band()).isEqualTo(ReadinessBand.NOT_READY);
    }

    @Test
    @DisplayName("a policy with no scoring requirements scores 100 rather than dividing by zero")
    void emptyRequirementsScoreFull() {
        ReadinessSnapshot snapshot = score(List.of(TestFixtures.optional(EvidenceType.TERMS_OF_SERVICE)),
                List.of(), List.of(), List.of());

        assertThat(snapshot.baseScore()).isEqualTo(100.0d);
        assertThat(snapshot.score()).isEqualTo(100);
    }

    @Test
    @DisplayName("superseded and invalidated artifacts never satisfy a requirement")
    void unusableStatusesDoNotSatisfy() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.INVOICE)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.INVOICE)
                                .status(EvidenceStatus.SUPERSEDED).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.INVOICE)
                                .status(EvidenceStatus.INVALIDATED).build(),
                        TestFixtures.evidence("EV-3", EvidenceType.INVOICE)
                                .status(EvidenceStatus.PENDING).build()),
                List.of(), List.of());

        assertThat(snapshot.score()).isZero();
        assertThat(snapshot.requirements().get(0).satisfied()).isFalse();
    }

    @Test
    @DisplayName("a requirement demanding provenance is not satisfied by unverifiable evidence")
    void provenanceRequirementBlocksSatisfaction() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatoryRequiringProvenance(EvidenceType.DELIVERY_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.DELIVERY_PROOF)
                        .provenanceVerified(false).build()),
                List.of(), List.of());

        assertThat(snapshot.score()).isZero();
        assertThat(snapshot.requirements().get(0).note())
                .contains("present but not usable");
    }

    @Test
    @DisplayName("the snapshot records which artifacts satisfied each requirement")
    void snapshotRecordsSatisfyingEvidence() {
        ReadinessSnapshot snapshot = score(
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build(),
                        TestFixtures.evidence("EV-9", EvidenceType.PAYMENT_PROOF).build()),
                List.of(), List.of());

        assertThat(snapshot.requirements()).hasSize(1);
        assertThat(snapshot.requirements().get(0).satisfyingEvidenceIds())
                .containsExactly("EV-1", "EV-9");
        assertThat(snapshot.policyVersionId()).isEqualTo("POL-TEST-V1");
        assertThat(snapshot.computedAt()).isEqualTo(NOW);
    }
}
