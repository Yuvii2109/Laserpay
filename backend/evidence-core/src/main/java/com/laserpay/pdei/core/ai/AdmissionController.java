package com.laserpay.pdei.core.ai;

import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.core.spi.AdmissionLogRecord;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.util.CoreErrors;
import com.laserpay.pdei.core.util.Scores;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Decides which cases are worth a model call (platform contract 9.4).
 *
 * <p>This is the economic heart of the platform. The expensive layer scales with <em>ambiguity</em>,
 * not with data volume: most cases are resolved deterministically and never reach the model.</p>
 *
 * <p><b>Deterministic short-circuits, evaluated first and always bypassing AI:</b></p>
 * <ol>
 *   <li>all MANDATORY requirements satisfied and zero contradictions - auto
 *       {@code PREPARE_REPRESENTMENT}; there is nothing for a model to add;</li>
 *   <li>no evidence at all - recommend {@code ACCEPT_LIABILITY} to a human; a model cannot reason
 *       about documents that do not exist;</li>
 *   <li>dispute already past its deadline - {@code ESCALATE_TO_HUMAN}; spending money on a case that
 *       can no longer be submitted is pure waste.</li>
 * </ol>
 *
 * <p><b>Priority formula</b>, for everything that survives the short-circuits:</p>
 * <pre>
 * priority = 0.40 * normalizedFinancialImpact
 *          + 0.25 * deadlineUrgency          (1.0 if under 48h remaining)
 *          + 0.20 * ambiguityScore           (contradictions + gap count, normalised)
 *          + 0.15 * (1 - deterministicConfidence)
 * </pre>
 * <p>The weighted sum is in [0,1] and is scaled to the 0-100 range the contract uses for the
 * threshold and for {@code POST /v1/admission/score}. A case is admitted when
 * {@code priority >= 55}, the Redis token bucket allows it and the daily budget has room.</p>
 */
public class AdmissionController {

    private static final Logger log = LoggerFactory.getLogger(AdmissionController.class);
    private static final String METRIC_ADMISSION = "pdei_ai_admission_total";

    public static final int DEFAULT_PRIORITY_THRESHOLD = 55;
    /** Dispute value at which financial impact saturates at 1.0. INR 100,000.00. */
    public static final long DEFAULT_FINANCIAL_IMPACT_CAP_MINOR = 10_000_000L;
    /** Contradictions + gaps at which the ambiguity term saturates at 1.0. */
    public static final int DEFAULT_AMBIGUITY_CAP = 8;
    /** Under this many hours remaining, urgency is 1.0 (contract 9.4). */
    public static final long URGENT_HOURS = 48L;
    /** Beyond this many hours remaining, urgency is 0.0. */
    public static final long RELAXED_HOURS = 720L;

    public static final double WEIGHT_FINANCIAL_IMPACT = 0.40d;
    public static final double WEIGHT_DEADLINE_URGENCY = 0.25d;
    public static final double WEIGHT_AMBIGUITY = 0.20d;
    public static final double WEIGHT_UNCERTAINTY = 0.15d;

    private final AiBudgetGate budgetGate;
    private final CaseRepositoryPort cases;
    private final MeterRegistry meterRegistry;
    private final int priorityThreshold;
    private final long financialImpactCapMinor;
    private final int ambiguityCap;

    public AdmissionController(AiBudgetGate budgetGate, CaseRepositoryPort cases, MeterRegistry meterRegistry,
                               int priorityThreshold, long financialImpactCapMinor, int ambiguityCap) {
        this.budgetGate = budgetGate == null ? AiBudgetGate.unlimited() : budgetGate;
        this.cases = cases;
        this.meterRegistry = meterRegistry;
        this.priorityThreshold = priorityThreshold <= 0 ? DEFAULT_PRIORITY_THRESHOLD : priorityThreshold;
        this.financialImpactCapMinor = financialImpactCapMinor <= 0
                ? DEFAULT_FINANCIAL_IMPACT_CAP_MINOR : financialImpactCapMinor;
        this.ambiguityCap = ambiguityCap <= 0 ? DEFAULT_AMBIGUITY_CAP : ambiguityCap;
    }

    /** Decide whether this case goes to the model, and log the decision either way. */
    public AdmissionDecision decide(AdmissionRequest request) {
        CoreErrors.requireValue(request, "request");

        double financialImpact = financialImpact(request);
        double urgency = deadlineUrgency(request);
        double ambiguity = ambiguityScore(request);
        double confidence = Scores.clamp(request.deterministicConfidence(), 0.0d, 1.0d);
        int priority = priority(financialImpact, urgency, ambiguity, confidence);

        AdmissionDecision decision = shortCircuit(request, priority, financialImpact, urgency, ambiguity,
                confidence);
        if (decision == null) {
            decision = throttle(request, priority, financialImpact, urgency, ambiguity, confidence);
        }
        recordDecision(request, decision);
        return decision;
    }

    /** The three deterministic short-circuits. Returns null when none applies. */
    private AdmissionDecision shortCircuit(AdmissionRequest request, int priority, double financialImpact,
                                           double urgency, double ambiguity, double confidence) {
        if (request.pastDeadline()) {
            return new AdmissionDecision(false, priority,
                    "dispute is past its representment deadline", ShortCircuit.PAST_DEADLINE,
                    RecommendedAction.ESCALATE_TO_HUMAN, financialImpact, urgency, ambiguity, confidence);
        }
        if (request.evidenceCount() <= 0) {
            return new AdmissionDecision(false, priority,
                    "no evidence is attached to the transaction", ShortCircuit.NO_EVIDENCE,
                    RecommendedAction.ACCEPT_LIABILITY, financialImpact, urgency, ambiguity, confidence);
        }
        if (request.allMandatorySatisfied() && request.contradictionCount() == 0) {
            return new AdmissionDecision(false, priority,
                    "all mandatory requirements satisfied with no contradictions",
                    ShortCircuit.ALL_REQUIREMENTS_SATISFIED, RecommendedAction.PREPARE_REPRESENTMENT,
                    financialImpact, urgency, ambiguity, confidence);
        }
        return null;
    }

    private AdmissionDecision throttle(AdmissionRequest request, int priority, double financialImpact,
                                       double urgency, double ambiguity, double confidence) {
        if (priority < priorityThreshold) {
            return new AdmissionDecision(false, priority,
                    "priority " + priority + " is below the admission threshold " + priorityThreshold,
                    ShortCircuit.BELOW_PRIORITY_THRESHOLD, null, financialImpact, urgency, ambiguity,
                    confidence);
        }
        if (!budgetGate.tryConsumeDailyBudget()) {
            return new AdmissionDecision(false, priority, "daily AI budget exhausted",
                    ShortCircuit.BUDGET_EXHAUSTED, null, financialImpact, urgency, ambiguity, confidence);
        }
        if (!budgetGate.tryConsumeToken()) {
            budgetGate.refund();
            return new AdmissionDecision(false, priority, "AI rate limit reached",
                    ShortCircuit.RATE_LIMITED, null, financialImpact, urgency, ambiguity, confidence);
        }
        return new AdmissionDecision(true, priority,
                "admitted with priority " + priority, ShortCircuit.NONE, null, financialImpact, urgency,
                ambiguity, confidence);
    }

    /** The contract 9.4 weighted sum, scaled to 0-100 and rounded half up. */
    public int priority(double financialImpact, double deadlineUrgency, double ambiguityScore,
                        double deterministicConfidence) {
        double weighted = WEIGHT_FINANCIAL_IMPACT * Scores.clamp(financialImpact, 0.0d, 1.0d)
                + WEIGHT_DEADLINE_URGENCY * Scores.clamp(deadlineUrgency, 0.0d, 1.0d)
                + WEIGHT_AMBIGUITY * Scores.clamp(ambiguityScore, 0.0d, 1.0d)
                + WEIGHT_UNCERTAINTY * (1.0d - Scores.clamp(deterministicConfidence, 0.0d, 1.0d));
        return Scores.roundAndClamp(weighted * 100.0d, 0, 100);
    }

    /** Convenience overload used by the orchestrator and by tests. */
    public int priority(AdmissionRequest request) {
        return priority(financialImpact(request), deadlineUrgency(request), ambiguityScore(request),
                request.deterministicConfidence());
    }

    /** Dispute value normalised against the cap, in [0,1]. Minor units only - never a float amount. */
    public double financialImpact(AdmissionRequest request) {
        long amountMinor = Math.max(0L, request.amountMinor());
        return Scores.clamp(amountMinor / (double) financialImpactCapMinor, 0.0d, 1.0d);
    }

    /**
     * Urgency in [0,1]: 1.0 under 48 hours remaining (contract 9.4), 0.0 beyond 30 days, linear in
     * between. An unknown deadline scores 0.5 - neither urgent nor safe to ignore.
     */
    public double deadlineUrgency(AdmissionRequest request) {
        if (request.deadlineAt() == null || request.now() == null) {
            return 0.5d;
        }
        long hours = Duration.between(request.now(), request.deadlineAt()).toHours();
        if (hours <= URGENT_HOURS) {
            return 1.0d;
        }
        if (hours >= RELAXED_HOURS) {
            return 0.0d;
        }
        return Scores.clamp((RELAXED_HOURS - hours) / (double) (RELAXED_HOURS - URGENT_HOURS), 0.0d, 1.0d);
    }

    /**
     * Ambiguity in [0,1] from contradictions and gaps. Contradictions count double: a case that
     * contradicts itself is harder to reason about than one that is merely incomplete.
     */
    public double ambiguityScore(AdmissionRequest request) {
        double weighted = request.contradictionCount() * 2.0d + request.gapCount();
        return Scores.clamp(weighted / ambiguityCap, 0.0d, 1.0d);
    }

    private void recordDecision(AdmissionRequest request, AdmissionDecision decision) {
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(METRIC_ADMISSION, "decision",
                        decision.admit() ? "ADMITTED" : decision.shortCircuit().name()).increment();
            } catch (RuntimeException e) {
                // metrics never block admission
            }
        }
        if (cases == null) {
            return;
        }
        try {
            cases.appendAdmissionLog(new AdmissionLogRecord(
                    Ids.withPrefix("ADM-"),
                    request.caseId(),
                    request.merchantId(),
                    request.transactionId(),
                    decision.admit(),
                    decision.priority(),
                    decision.reason(),
                    decision.shortCircuit().name(),
                    decision.financialImpact(),
                    decision.deadlineUrgency(),
                    decision.ambiguityScore(),
                    decision.deterministicConfidence(),
                    request.amountMinor(),
                    request.disputeAmount() == null ? null : request.disputeAmount().currency(),
                    request.now()));
        } catch (RuntimeException e) {
            log.warn("could not persist admission log for case {}: {}", request.caseId(), e.toString());
        }
    }
}
