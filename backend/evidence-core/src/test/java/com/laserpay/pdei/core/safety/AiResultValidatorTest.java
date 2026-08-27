package com.laserpay.pdei.core.safety;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.common.domain.SafetyDecision;
import com.laserpay.pdei.core.TestFixtures;
import com.laserpay.pdei.core.model.Citation;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.InvestigationResult;
import com.laserpay.pdei.core.model.ModelMetadata;
import com.laserpay.pdei.core.model.RequirementView;
import com.laserpay.pdei.core.model.SafetyVerdict;
import com.laserpay.pdei.core.policy.DefaultPolicyMatrix;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** One test per rejection rule of platform contract 9.3, plus the clean path. */
class AiResultValidatorTest {

    private EvidenceRepositoryPort evidence;
    private AiResultValidator validator;

    @BeforeEach
    void setUp() {
        evidence = mock(EvidenceRepositoryPort.class);
        validator = new AiResultValidator(evidence, null);
    }

    private static PolicyView basePolicy() {
        return DefaultPolicyMatrix.defaultPolicy(TestFixtures.MERCHANT,
                DisputeReasonCode.GOODS_NOT_RECEIVED, null);
    }

    private static PolicyView policyWith(Set<RecommendedAction> permitted,
                                         Set<EvidenceType> prohibited,
                                         double minConfidence, int maxContradictions) {
        PolicyView base = basePolicy();
        return new PolicyView(base.policyId(), base.policyVersionId(), base.version(), base.merchantId(),
                base.reasonCode(), base.requirements(), permitted, prohibited, minConfidence,
                maxContradictions, base.minReadinessScoreForAutoPrepare(),
                base.humanReviewAboveAmountMinor(), base.currency(), base.autoSubmitEnabled(),
                base.responseWindowDays(), base.expiringSoonDays(), base.createdBy(), base.checksum(),
                base.effectiveFrom(), base.effectiveTo(), base.defaultPolicy());
    }

    private static ValidationInput input(PolicyView policy, List<RequirementView> requirements) {
        return new ValidationInput("CASE-1", "DSP-1", TestFixtures.TRANSACTION, TestFixtures.MERCHANT,
                policy, requirements);
    }

    private static List<RequirementView> satisfiedRequirements() {
        return List.of(new RequirementView(EvidenceType.PAYMENT_PROOF, RequirementStrength.MANDATORY,
                true, List.of("EV-1"), 3, null));
    }

    private static InvestigationResult result(InvestigationClassification classification, double confidence,
                                              RecommendedAction action, List<String> supporting,
                                              List<ContradictionView> contradictions) {
        return new InvestigationResult("INV-1", classification, confidence, supporting, List.of(),
                contradictions, "summary", "narrative", action,
                supporting.stream().map(id -> new Citation("claim about " + id, id)).toList(),
                new ModelMetadata("mock", "mock-1", 0, 0, 12L, 1));
    }

    private void evidenceExists(EvidenceView... views) {
        when(evidence.findByIds(anyCollection())).thenReturn(List.of(views));
    }

    @Test
    @DisplayName("a fully supported result is allowed")
    void allowsCleanResult() {
        evidenceExists(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build());

        SafetyVerdict verdict = validator.validate(
                result(InvestigationClassification.DEFENDABLE, 0.99d,
                        RecommendedAction.PREPARE_REPRESENTMENT, List.of("EV-1"), List.of()),
                input(basePolicy(), satisfiedRequirements()));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.ALLOW);
        assertThat(verdict.reasons()).isEmpty();
        assertThat(verdict.unsupportedClaims()).isEmpty();
    }

    @Test
    @DisplayName("rule 1: an evidence id that does not exist in Postgres is rejected")
    void rule1UnknownEvidence() {
        evidenceExists();

        SafetyVerdict verdict = validator.validate(
                result(InvestigationClassification.DEFENDABLE, 0.99d,
                        RecommendedAction.PREPARE_REPRESENTMENT, List.of("EV-HALLUCINATED"), List.of()),
                input(basePolicy(), satisfiedRequirements()));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.DENY);
        assertThat(verdict.reasons())
                .anyMatch(r -> r.startsWith(AiResultValidator.RULE_1_UNKNOWN_EVIDENCE));
        assertThat(verdict.unsupportedClaims()).isNotEmpty();
    }

    @Test
    @DisplayName("rule 2: evidence belonging to another transaction is rejected")
    void rule2EvidenceNotLinked() {
        evidenceExists(TestFixtures.evidence("EV-OTHER", EvidenceType.PAYMENT_PROOF)
                .transactionId("TX-999999").build());

        SafetyVerdict verdict = validator.validate(
                result(InvestigationClassification.DEFENDABLE, 0.99d,
                        RecommendedAction.PREPARE_REPRESENTMENT, List.of("EV-OTHER"), List.of()),
                input(basePolicy(), satisfiedRequirements()));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.DENY);
        assertThat(verdict.reasons())
                .anyMatch(r -> r.startsWith(AiResultValidator.RULE_2_EVIDENCE_NOT_LINKED));
    }

    @Test
    @DisplayName("rule 3: an action the policy does not permit is rejected")
    void rule3ActionNotPermitted() {
        evidenceExists(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build());
        PolicyView restricted = policyWith(EnumSet.of(RecommendedAction.ESCALATE_TO_HUMAN), Set.of(),
                0.90d, 0);

        SafetyVerdict verdict = validator.validate(
                result(InvestigationClassification.DEFENDABLE, 0.99d,
                        RecommendedAction.PREPARE_REPRESENTMENT, List.of("EV-1"), List.of()),
                input(restricted, satisfiedRequirements()));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.DENY);
        assertThat(verdict.reasons())
                .anyMatch(r -> r.startsWith(AiResultValidator.RULE_3_ACTION_NOT_PERMITTED));
    }

    @Test
    @DisplayName("rule 4: confidence below autoPrepareMinConfidence blocks PREPARE_REPRESENTMENT")
    void rule4ConfidenceBelowThreshold() {
        evidenceExists(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build());

        SafetyVerdict verdict = validator.validate(
                result(InvestigationClassification.DEFENDABLE, 0.72d,
                        RecommendedAction.PREPARE_REPRESENTMENT, List.of("EV-1"), List.of()),
                input(basePolicy(), satisfiedRequirements()));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.DENY);
        assertThat(verdict.reasons())
                .anyMatch(r -> r.startsWith(AiResultValidator.RULE_4_CONFIDENCE_BELOW_THRESHOLD));
    }

    @Test
    @DisplayName("rule 4 does not apply to actions other than PREPARE_REPRESENTMENT")
    void rule4OnlyAppliesToPreparation() {
        evidenceExists(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build());

        SafetyVerdict verdict = validator.validate(
                result(InvestigationClassification.WEAK, 0.30d,
                        RecommendedAction.GATHER_MORE_EVIDENCE, List.of("EV-1"), List.of()),
                input(basePolicy(), satisfiedRequirements()));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.ALLOW);
    }

    @Test
    @DisplayName("rule 5: more contradictions than the policy allows blocks PREPARE_REPRESENTMENT")
    void rule5TooManyContradictions() {
        evidenceExists(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build());

        SafetyVerdict verdict = validator.validate(
                result(InvestigationClassification.DEFENDABLE, 0.99d,
                        RecommendedAction.PREPARE_REPRESENTMENT, List.of("EV-1"),
                        List.of(ContradictionView.narrative("delivery precedes dispatch"))),
                input(basePolicy(), satisfiedRequirements()));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.DENY);
        assertThat(verdict.reasons())
                .anyMatch(r -> r.startsWith(AiResultValidator.RULE_5_TOO_MANY_CONTRADICTIONS));
    }

    @Test
    @DisplayName("rule 6: a prohibited evidence type in supportingEvidence is rejected")
    void rule6ProhibitedEvidenceType() {
        evidenceExists(TestFixtures.evidence("EV-DF", EvidenceType.DEVICE_FINGERPRINT).build());
        PolicyView restricted = policyWith(DefaultPolicyMatrix.DEFAULT_PERMITTED_ACTIONS,
                Set.of(EvidenceType.DEVICE_FINGERPRINT), 0.90d, 0);

        SafetyVerdict verdict = validator.validate(
                result(InvestigationClassification.DEFENDABLE, 0.99d,
                        RecommendedAction.PREPARE_REPRESENTMENT, List.of("EV-DF"), List.of()),
                input(restricted, satisfiedRequirements()));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.DENY);
        assertThat(verdict.reasons())
                .anyMatch(r -> r.startsWith(AiResultValidator.RULE_6_PROHIBITED_EVIDENCE_TYPE));
    }

    @Test
    @DisplayName("rule 7: DEFENDABLE while a mandatory requirement is unsatisfied is rejected")
    void rule7DefendableWithUnsatisfiedMandatory() {
        evidenceExists(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build());
        List<RequirementView> requirements = List.of(
                new RequirementView(EvidenceType.PAYMENT_PROOF, RequirementStrength.MANDATORY, true,
                        List.of("EV-1"), 3, null),
                new RequirementView(EvidenceType.DELIVERY_PROOF, RequirementStrength.MANDATORY, false,
                        List.of(), 3, "not attached"));

        SafetyVerdict verdict = validator.validate(
                result(InvestigationClassification.DEFENDABLE, 0.99d,
                        RecommendedAction.PREPARE_REPRESENTMENT, List.of("EV-1"), List.of()),
                input(basePolicy(), requirements));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.DENY);
        assertThat(verdict.reasons())
                .anyMatch(r -> r.startsWith(
                        AiResultValidator.RULE_7_DEFENDABLE_WITH_UNSATISFIED_MANDATORY));
    }

    @Test
    @DisplayName("citations are checked as strictly as supportingEvidence")
    void citationsAreValidatedToo() {
        evidenceExists(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build());
        InvestigationResult withBadCitation = new InvestigationResult("INV-1",
                InvestigationClassification.DEFENDABLE, 0.99d, List.of("EV-1"), List.of(), List.of(),
                "summary", "narrative", RecommendedAction.PREPARE_REPRESENTMENT,
                List.of(new Citation("delivery was signed for", "EV-DOES-NOT-EXIST")),
                new ModelMetadata("mock", "mock-1", 0, 0, 5L, 1));

        SafetyVerdict verdict = validator.validate(withBadCitation,
                input(basePolicy(), satisfiedRequirements()));

        assertThat(verdict.decision()).isEqualTo(SafetyDecision.DENY);
        assertThat(verdict.unsupportedClaims())
                .anyMatch(claim -> claim.contains("delivery was signed for"));
    }
}
