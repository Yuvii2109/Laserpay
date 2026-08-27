package com.laserpay.pdei.core.ai;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.core.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The priority formula and short-circuits of platform contract 9.4. */
class AdmissionControllerTest {

    private static final Instant NOW = TestFixtures.NOW;

    private static AdmissionController controller() {
        return new AdmissionController(AiBudgetGate.unlimited(), null, null, 55,
                AdmissionController.DEFAULT_FINANCIAL_IMPACT_CAP_MINOR,
                AdmissionController.DEFAULT_AMBIGUITY_CAP);
    }

    private static AdmissionRequest request(long amountMinor, Instant deadlineAt, int contradictions,
                                            int gaps, int evidenceCount, int unsatisfiedMandatory,
                                            double deterministicConfidence) {
        return new AdmissionRequest("CASE-1", TestFixtures.MERCHANT, TestFixtures.TRANSACTION,
                DisputeReasonCode.GOODS_NOT_RECEIVED, Money.of(amountMinor, "INR"), deadlineAt,
                contradictions, gaps, evidenceCount, unsatisfiedMandatory, deterministicConfidence, NOW);
    }

    // --- deterministic short-circuits -------------------------------------------------------------

    @Test
    @DisplayName("short-circuit 1: everything satisfied with no contradictions auto-prepares, no AI")
    void allRequirementsSatisfiedBypassesAi() {
        AdmissionDecision decision = controller().decide(
                request(9_000_000L, NOW.plusSeconds(3600), 0, 0, 5, 0, 0.99d));

        assertThat(decision.admit()).isFalse();
        assertThat(decision.shortCircuit()).isEqualTo(ShortCircuit.ALL_REQUIREMENTS_SATISFIED);
        assertThat(decision.deterministicAction()).isEqualTo(RecommendedAction.PREPARE_REPRESENTMENT);
        assertThat(decision.resolvedDeterministically()).isTrue();
    }

    @Test
    @DisplayName("short-circuit 2: no evidence at all recommends ACCEPT_LIABILITY to a human")
    void noEvidenceBypassesAi() {
        AdmissionDecision decision = controller().decide(
                request(9_000_000L, NOW.plusSeconds(86400), 3, 6, 0, 4, 0.10d));

        assertThat(decision.admit()).isFalse();
        assertThat(decision.shortCircuit()).isEqualTo(ShortCircuit.NO_EVIDENCE);
        assertThat(decision.deterministicAction()).isEqualTo(RecommendedAction.ACCEPT_LIABILITY);
    }

    @Test
    @DisplayName("short-circuit 3: a dispute past its deadline escalates instead of spending a call")
    void pastDeadlineBypassesAi() {
        AdmissionDecision decision = controller().decide(
                request(9_000_000L, NOW.minusSeconds(3600), 2, 4, 5, 2, 0.20d));

        assertThat(decision.admit()).isFalse();
        assertThat(decision.shortCircuit()).isEqualTo(ShortCircuit.PAST_DEADLINE);
        assertThat(decision.deterministicAction()).isEqualTo(RecommendedAction.ESCALATE_TO_HUMAN);
    }

    @Test
    @DisplayName("the past-deadline short-circuit wins over the satisfied short-circuit")
    void pastDeadlineTakesPrecedence() {
        AdmissionDecision decision = controller().decide(
                request(1_000L, NOW.minusSeconds(1), 0, 0, 3, 0, 1.0d));

        assertThat(decision.shortCircuit()).isEqualTo(ShortCircuit.PAST_DEADLINE);
    }

    // --- priority formula -------------------------------------------------------------------------

    @Test
    @DisplayName("every term at its maximum gives a priority of 100")
    void maximumPriority() {
        AdmissionController controller = controller();
        AdmissionRequest request = request(20_000_000L, NOW.plusSeconds(3600), 4, 0, 5, 3, 0.0d);

        assertThat(controller.financialImpact(request)).isEqualTo(1.0d);
        assertThat(controller.deadlineUrgency(request)).isEqualTo(1.0d);
        assertThat(controller.ambiguityScore(request)).isEqualTo(1.0d);
        assertThat(controller.priority(request)).isEqualTo(100);

        AdmissionDecision decision = controller.decide(request);
        assertThat(decision.admit()).isTrue();
        assertThat(decision.shortCircuit()).isEqualTo(ShortCircuit.NONE);
    }

    @Test
    @DisplayName("the weighted sum matches the contract formula for a mid-range case")
    void midRangePriority() {
        AdmissionController controller = controller();
        // fin = 0.5, urgency = (720-72)/(720-48) = 0.964285..., ambiguity = (2*2+2)/8 = 0.75,
        // uncertainty = 1 - 0.4 = 0.6
        // 0.40*0.5 + 0.25*0.964285 + 0.20*0.75 + 0.15*0.6 = 0.681071 -> 68
        AdmissionRequest request = request(5_000_000L, NOW.plusSeconds(72 * 3600), 2, 2, 4, 1, 0.4d);

        assertThat(controller.financialImpact(request)).isEqualTo(0.5d);
        assertThat(controller.deadlineUrgency(request)).isCloseTo(0.9642857d, within(1e-6));
        assertThat(controller.ambiguityScore(request)).isEqualTo(0.75d);
        assertThat(controller.priority(request)).isEqualTo(68);
        assertThat(controller.decide(request).admit()).isTrue();
    }

    @Test
    @DisplayName("a low-value, low-ambiguity, far-deadline case falls below the threshold")
    void belowThresholdIsRefused() {
        AdmissionController controller = controller();
        AdmissionRequest request = request(1_000L, NOW.plusSeconds(60L * 24 * 3600), 0, 1, 3, 1, 0.9d);

        AdmissionDecision decision = controller.decide(request);

        assertThat(decision.priority()).isLessThan(55);
        assertThat(decision.admit()).isFalse();
        assertThat(decision.shortCircuit()).isEqualTo(ShortCircuit.BELOW_PRIORITY_THRESHOLD);
        assertThat(decision.deterministicAction()).isNull();
    }

    @Test
    @DisplayName("financial impact saturates at the configured cap")
    void financialImpactSaturates() {
        AdmissionController controller = controller();

        assertThat(controller.financialImpact(request(0L, NOW, 0, 0, 1, 1, 0.5d))).isEqualTo(0.0d);
        assertThat(controller.financialImpact(request(2_500_000L, NOW, 0, 0, 1, 1, 0.5d)))
                .isEqualTo(0.25d);
        assertThat(controller.financialImpact(request(99_999_999L, NOW, 0, 0, 1, 1, 0.5d)))
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("deadline urgency is 1.0 under 48 hours, 0.0 beyond 30 days, 0.5 when unknown")
    void deadlineUrgencyBoundaries() {
        AdmissionController controller = controller();

        assertThat(controller.deadlineUrgency(request(0L, NOW.plusSeconds(47 * 3600), 0, 0, 1, 1, 0.5d)))
                .isEqualTo(1.0d);
        assertThat(controller.deadlineUrgency(
                request(0L, NOW.plusSeconds(40L * 24 * 3600), 0, 0, 1, 1, 0.5d))).isEqualTo(0.0d);
        assertThat(controller.deadlineUrgency(request(0L, null, 0, 0, 1, 1, 0.5d))).isEqualTo(0.5d);
    }

    @Test
    @DisplayName("contradictions weigh double gaps in the ambiguity term")
    void ambiguityWeighsContradictionsDouble() {
        AdmissionController controller = controller();

        assertThat(controller.ambiguityScore(request(0L, NOW, 1, 0, 1, 1, 0.5d))).isEqualTo(0.25d);
        assertThat(controller.ambiguityScore(request(0L, NOW, 0, 2, 1, 1, 0.5d))).isEqualTo(0.25d);
        assertThat(controller.ambiguityScore(request(0L, NOW, 9, 9, 1, 1, 0.5d))).isEqualTo(1.0d);
    }

    // --- throttling -------------------------------------------------------------------------------

    @Test
    @DisplayName("an exhausted daily budget refuses admission even at high priority")
    void budgetExhausted() {
        AdmissionController controller = new AdmissionController(new StubGate(false, true), null, null,
                55, AdmissionController.DEFAULT_FINANCIAL_IMPACT_CAP_MINOR,
                AdmissionController.DEFAULT_AMBIGUITY_CAP);

        AdmissionDecision decision = controller.decide(
                request(20_000_000L, NOW.plusSeconds(3600), 4, 4, 5, 3, 0.0d));

        assertThat(decision.priority()).isEqualTo(100);
        assertThat(decision.admit()).isFalse();
        assertThat(decision.shortCircuit()).isEqualTo(ShortCircuit.BUDGET_EXHAUSTED);
    }

    @Test
    @DisplayName("an empty token bucket refuses admission and refunds the budget slot")
    void rateLimited() {
        StubGate gate = new StubGate(true, false);
        AdmissionController controller = new AdmissionController(gate, null, null, 55,
                AdmissionController.DEFAULT_FINANCIAL_IMPACT_CAP_MINOR,
                AdmissionController.DEFAULT_AMBIGUITY_CAP);

        AdmissionDecision decision = controller.decide(
                request(20_000_000L, NOW.plusSeconds(3600), 4, 4, 5, 3, 0.0d));

        assertThat(decision.admit()).isFalse();
        assertThat(decision.shortCircuit()).isEqualTo(ShortCircuit.RATE_LIMITED);
        assertThat(gate.refunds).isEqualTo(1);
    }

    /** Deterministic gate stub: no Redis, no timing, no flakiness. */
    private static final class StubGate implements AiBudgetGate {
        private final boolean budgetAllows;
        private final boolean tokenAllows;
        private int refunds;

        private StubGate(boolean budgetAllows, boolean tokenAllows) {
            this.budgetAllows = budgetAllows;
            this.tokenAllows = tokenAllows;
        }

        @Override
        public boolean tryConsumeToken() {
            return tokenAllows;
        }

        @Override
        public boolean tryConsumeDailyBudget() {
            return budgetAllows;
        }

        @Override
        public void refund() {
            refunds++;
        }

        @Override
        public long usedToday() {
            return 0L;
        }

        @Override
        public long dailyBudget() {
            return 100L;
        }
    }
}
