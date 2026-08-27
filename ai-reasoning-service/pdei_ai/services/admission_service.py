"""Admission scoring - a faithful mirror of platform contract 9.4.

    priority = 0.40 * normalizedFinancialImpact
             + 0.25 * deadlineUrgency        (1.0 if <48h remaining)
             + 0.20 * ambiguityScore         (contradictions + gap count, normalized)
             + 0.15 * (1 - deterministicConfidence)

    admit if priority >= 55 AND the Redis token bucket allows AND the
    deterministic path is unresolved.

**Why this exists twice.** The authoritative implementation is Java
(``core.ai.AdmissionController``): a cost and safety control must not depend on
the service it is throttling, and the orchestrator must be able to decide *not*
to call this service at all. This module mirrors the same arithmetic so the
Python side can score standalone - for the simulator, for demos, and for
``POST /v1/admission/score``, which the Java client treats as advisory.

Every constant below is duplicated from ``AdmissionController`` deliberately and
is named identically. If the two ever drift, the Java side wins and this file is
the one to fix.

**The three deterministic short-circuits are evaluated first and always bypass
the model**, in the same order as Java: past deadline, no evidence at all, then
all mandatory requirements satisfied with zero contradictions.
"""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal

from pdei_ai.models.admission import AdmissionDecision, AdmissionRequest, ShortCircuit
from pdei_ai.models.common import utc_now
from pdei_ai.models.enums import RecommendedAction
from pdei_ai.observability.logging import get_logger
from pdei_ai.observability.metrics import record_admission
from pdei_ai.services.budget import BudgetGate

log = get_logger(__name__)

# --- contract 9.4 constants, mirrored from AdmissionController ---------------

DEFAULT_PRIORITY_THRESHOLD = 55
#: Dispute value at which financial impact saturates at 1.0. INR 100,000.00.
DEFAULT_FINANCIAL_IMPACT_CAP_MINOR = 10_000_000
#: Contradictions + gaps at which the ambiguity term saturates at 1.0.
DEFAULT_AMBIGUITY_CAP = 8
#: Under this many hours remaining, urgency is 1.0 (contract 9.4).
URGENT_HOURS = 48.0
#: Beyond this many hours remaining, urgency is 0.0.
RELAXED_HOURS = 720.0

WEIGHT_FINANCIAL_IMPACT = 0.40
WEIGHT_DEADLINE_URGENCY = 0.25
WEIGHT_AMBIGUITY = 0.20
WEIGHT_UNCERTAINTY = 0.15


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return low if value < low else high if value > high else value


def round_half_up(value: float) -> int:
    """Half-up rounding, matching Java. Python's ``round`` is banker's rounding.

    Not pedantry: at the 55 threshold, 54.5 rounding to 54 instead of 55 changes
    whether a case reaches the model at all.
    """
    return int(Decimal(str(value)).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


class AdmissionService:
    """Scores a case and decides whether it is worth a model call."""

    def __init__(
        self,
        budget: BudgetGate | None = None,
        priority_threshold: int = DEFAULT_PRIORITY_THRESHOLD,
        financial_impact_cap_minor: int = DEFAULT_FINANCIAL_IMPACT_CAP_MINOR,
        ambiguity_cap: int = DEFAULT_AMBIGUITY_CAP,
    ) -> None:
        self._budget = budget
        self.priority_threshold = (
            priority_threshold if priority_threshold > 0 else DEFAULT_PRIORITY_THRESHOLD
        )
        self.financial_impact_cap_minor = (
            financial_impact_cap_minor
            if financial_impact_cap_minor > 0
            else DEFAULT_FINANCIAL_IMPACT_CAP_MINOR
        )
        self.ambiguity_cap = ambiguity_cap if ambiguity_cap > 0 else DEFAULT_AMBIGUITY_CAP

    # --- the four terms -----------------------------------------------------

    def financial_impact(self, request: AdmissionRequest) -> float:
        """Dispute value normalised against the cap. Minor units only, never a float amount."""
        amount_minor = max(0, request.amount_minor)
        return clamp(amount_minor / float(self.financial_impact_cap_minor))

    def deadline_urgency(self, request: AdmissionRequest) -> float:
        """1.0 under 48h remaining, 0.0 beyond 30 days, linear in between.

        An unknown deadline scores 0.5 - neither urgent nor safe to ignore.
        """
        if request.deadlineAt is None:
            return 0.5
        reference = request.now or utc_now()
        hours = (request.deadlineAt - reference).total_seconds() / 3600.0
        if hours <= URGENT_HOURS:
            return 1.0
        if hours >= RELAXED_HOURS:
            return 0.0
        return clamp((RELAXED_HOURS - hours) / (RELAXED_HOURS - URGENT_HOURS))

    def ambiguity_score(self, request: AdmissionRequest) -> float:
        """Contradictions count double: a self-contradicting case is harder than an
        incomplete one, and it is exactly the case a model can help with."""
        weighted = request.contradictionCount * 2.0 + request.gapCount
        return clamp(weighted / float(self.ambiguity_cap))

    def priority(self, request: AdmissionRequest) -> int:
        """The contract 9.4 weighted sum, scaled to 0-100 and rounded half up."""
        weighted = (
            WEIGHT_FINANCIAL_IMPACT * self.financial_impact(request)
            + WEIGHT_DEADLINE_URGENCY * self.deadline_urgency(request)
            + WEIGHT_AMBIGUITY * self.ambiguity_score(request)
            + WEIGHT_UNCERTAINTY * (1.0 - clamp(request.deterministicConfidence))
        )
        return int(clamp(round_half_up(weighted * 100.0), 0, 100))

    # --- the decision -------------------------------------------------------

    async def decide(self, request: AdmissionRequest) -> AdmissionDecision:
        financial_impact = self.financial_impact(request)
        urgency = self.deadline_urgency(request)
        ambiguity = self.ambiguity_score(request)
        confidence = clamp(request.deterministicConfidence)
        priority = self.priority(request)

        terms = {
            "financialImpact": financial_impact,
            "deadlineUrgency": urgency,
            "ambiguityScore": ambiguity,
            "deterministicConfidence": confidence,
            "threshold": self.priority_threshold,
        }

        decision = self._short_circuit(request, priority, terms)
        if decision is None:
            decision = await self._throttle(request, priority, terms)

        record_admission("ADMITTED" if decision.admit else decision.shortCircuit.value)
        log.info(
            "admission decision",
            caseId=request.caseId,
            investigationId=request.investigationId,
            admit=decision.admit,
            priority=decision.priority,
            shortCircuit=decision.shortCircuit.value,
            reason=decision.reason,
        )
        return decision

    def _short_circuit(
        self, request: AdmissionRequest, priority: int, terms: dict[str, float]
    ) -> AdmissionDecision | None:
        """The three deterministic short-circuits, in contract order."""
        if request.past_deadline():
            return AdmissionDecision(
                admit=False,
                priority=priority,
                reason="dispute is past its representment deadline",
                shortCircuit=ShortCircuit.PAST_DEADLINE,
                deterministicAction=RecommendedAction.ESCALATE_TO_HUMAN,
                **terms,  # type: ignore[arg-type]
            )
        if request.evidenceCount <= 0:
            return AdmissionDecision(
                admit=False,
                priority=priority,
                reason="no evidence is attached to the transaction",
                shortCircuit=ShortCircuit.NO_EVIDENCE,
                deterministicAction=RecommendedAction.ACCEPT_LIABILITY,
                **terms,  # type: ignore[arg-type]
            )
        if request.all_mandatory_satisfied and request.contradictionCount == 0:
            return AdmissionDecision(
                admit=False,
                priority=priority,
                reason="all mandatory requirements satisfied with no contradictions",
                shortCircuit=ShortCircuit.ALL_REQUIREMENTS_SATISFIED,
                deterministicAction=RecommendedAction.PREPARE_REPRESENTMENT,
                **terms,  # type: ignore[arg-type]
            )
        return None

    async def _throttle(
        self, request: AdmissionRequest, priority: int, terms: dict[str, float]
    ) -> AdmissionDecision:
        if priority < self.priority_threshold:
            return AdmissionDecision(
                admit=False,
                priority=priority,
                reason=(
                    f"priority {priority} is below the admission threshold "
                    f"{self.priority_threshold}"
                ),
                shortCircuit=ShortCircuit.BELOW_PRIORITY_THRESHOLD,
                **terms,  # type: ignore[arg-type]
            )

        if self._budget is not None:
            if not await self._budget.try_consume_daily_budget():
                return AdmissionDecision(
                    admit=False,
                    priority=priority,
                    reason="daily AI budget exhausted",
                    shortCircuit=ShortCircuit.BUDGET_EXHAUSTED,
                    **terms,  # type: ignore[arg-type]
                )
            if not await self._budget.try_consume_token():
                # Give the daily allowance back: the call never happened.
                await self._budget.refund()
                return AdmissionDecision(
                    admit=False,
                    priority=priority,
                    reason="AI rate limit reached",
                    shortCircuit=ShortCircuit.RATE_LIMITED,
                    **terms,  # type: ignore[arg-type]
                )

        return AdmissionDecision(
            admit=True,
            priority=priority,
            reason=f"admitted with priority {priority}",
            shortCircuit=ShortCircuit.NONE,
            **terms,  # type: ignore[arg-type]
        )
