"""Admission scoring boundaries (platform contract 9.4).

The 55 threshold decides whether a case costs money. These tests pin the
arithmetic, the rounding mode and the order of the three deterministic
short-circuits, because all three are places where Python and Java could
silently disagree.
"""

from __future__ import annotations

from datetime import timedelta

import pytest

from pdei_ai.models.admission import (
    AdmissionRequest,
    ShortCircuit,
    admission_request_from_payload,
)
from pdei_ai.models.common import Money, utc_now
from pdei_ai.models.enums import RecommendedAction
from pdei_ai.models.investigation import InvestigationContext
from pdei_ai.services.admission_service import (
    DEFAULT_FINANCIAL_IMPACT_CAP_MINOR,
    AdmissionService,
    round_half_up,
)

NOW = utc_now()


def _request(**overrides) -> AdmissionRequest:
    base = {
        "caseId": "CASE-1",
        "merchantId": "MER-0001",
        "transactionId": "TX-1",
        "disputeAmount": Money(amountMinor=1_000_000, currency="INR"),
        "deadlineAt": NOW + timedelta(days=10),
        "contradictionCount": 1,
        "gapCount": 1,
        "evidenceCount": 3,
        "unsatisfiedMandatoryCount": 1,
        "deterministicConfidence": 0.5,
        "now": NOW,
    }
    base.update(overrides)
    return AdmissionRequest(**base)


@pytest.fixture
def service() -> AdmissionService:
    return AdmissionService(budget=None)


# --- rounding ---------------------------------------------------------------


def test_rounding_is_half_up_not_bankers() -> None:
    """Python's round(54.5) is 54. Java's is 55. At the threshold that matters."""
    assert round_half_up(54.5) == 55
    assert round_half_up(55.5) == 56
    assert round_half_up(54.4) == 54
    assert round(54.5) == 54  # documents why round_half_up exists


# --- the four terms ---------------------------------------------------------


def test_financial_impact_saturates_at_the_cap(service: AdmissionService) -> None:
    assert service.financial_impact(
        _request(disputeAmount=Money(amountMinor=0, currency="INR"))
    ) == 0.0
    assert service.financial_impact(
        _request(disputeAmount=Money(amountMinor=DEFAULT_FINANCIAL_IMPACT_CAP_MINOR // 2,
                                     currency="INR"))
    ) == pytest.approx(0.5)
    assert service.financial_impact(
        _request(disputeAmount=Money(amountMinor=DEFAULT_FINANCIAL_IMPACT_CAP_MINOR * 5,
                                     currency="INR"))
    ) == 1.0


def test_deadline_urgency_boundaries(service: AdmissionService) -> None:
    assert service.deadline_urgency(_request(deadlineAt=NOW + timedelta(hours=47))) == 1.0
    assert service.deadline_urgency(_request(deadlineAt=NOW + timedelta(hours=48))) == 1.0
    assert service.deadline_urgency(_request(deadlineAt=NOW + timedelta(hours=720))) == 0.0
    assert service.deadline_urgency(_request(deadlineAt=NOW + timedelta(days=60))) == 0.0
    middle = service.deadline_urgency(_request(deadlineAt=NOW + timedelta(hours=384)))
    assert 0.0 < middle < 1.0
    # An unknown deadline is neither urgent nor safe to ignore.
    assert service.deadline_urgency(_request(deadlineAt=None)) == 0.5


def test_ambiguity_counts_contradictions_double(service: AdmissionService) -> None:
    assert service.ambiguity_score(_request(contradictionCount=0, gapCount=0)) == 0.0
    assert service.ambiguity_score(
        _request(contradictionCount=1, gapCount=0)
    ) == pytest.approx(0.25)
    assert service.ambiguity_score(
        _request(contradictionCount=0, gapCount=2)
    ) == pytest.approx(0.25)
    assert service.ambiguity_score(_request(contradictionCount=8, gapCount=8)) == 1.0


def test_priority_uses_the_contract_weights(service: AdmissionService) -> None:
    """0.40*1 + 0.25*1 + 0.20*1 + 0.15*(1-0) = 1.0 -> 100."""
    maxed = _request(
        disputeAmount=Money(amountMinor=DEFAULT_FINANCIAL_IMPACT_CAP_MINOR, currency="INR"),
        deadlineAt=NOW + timedelta(hours=1),
        contradictionCount=8,
        gapCount=8,
        deterministicConfidence=0.0,
    )
    assert service.priority(maxed) == 100

    floored = _request(
        disputeAmount=Money(amountMinor=0, currency="INR"),
        deadlineAt=NOW + timedelta(days=60),
        contradictionCount=0,
        gapCount=0,
        deterministicConfidence=1.0,
    )
    assert service.priority(floored) == 0


# --- the threshold ----------------------------------------------------------


async def test_admission_threshold_boundary_is_inclusive() -> None:
    """priority >= 55 admits; 54 does not."""
    service = AdmissionService(budget=None)

    at_threshold = _request(
        disputeAmount=Money(amountMinor=5_000_000, currency="INR"),  # 0.40*0.5 = 0.20
        deadlineAt=NOW + timedelta(hours=1),  # 0.25*1.0 = 0.25
        contradictionCount=1,
        gapCount=2,  # 0.20*0.5 = 0.10
        deterministicConfidence=1.0,  # 0.15*0.0 = 0.00
        evidenceCount=3,
        unsatisfiedMandatoryCount=1,
    )
    assert service.priority(at_threshold) == 55
    decision = await service.decide(at_threshold)
    assert decision.admit is True
    assert decision.shortCircuit is ShortCircuit.NONE

    # One notch below: 0.40*0.48 + 0.25*1.0 + 0.20*0.5 + 0.15*0.0 = 0.542 -> 54.
    below = at_threshold.model_copy(
        update={"disputeAmount": Money(amountMinor=4_800_000, currency="INR")}
    )
    assert service.priority(below) == 54
    decision = await service.decide(below)
    assert decision.admit is False
    assert decision.shortCircuit is ShortCircuit.BELOW_PRIORITY_THRESHOLD


# --- the three deterministic short-circuits ---------------------------------


async def test_past_deadline_short_circuits_to_escalation(service: AdmissionService) -> None:
    decision = await service.decide(_request(deadlineAt=NOW - timedelta(hours=1)))
    assert decision.admit is False
    assert decision.shortCircuit is ShortCircuit.PAST_DEADLINE
    assert decision.deterministicAction is RecommendedAction.ESCALATE_TO_HUMAN


async def test_no_evidence_short_circuits_to_accept_liability(
    service: AdmissionService,
) -> None:
    decision = await service.decide(_request(evidenceCount=0))
    assert decision.shortCircuit is ShortCircuit.NO_EVIDENCE
    assert decision.deterministicAction is RecommendedAction.ACCEPT_LIABILITY


async def test_all_requirements_satisfied_short_circuits_to_prepare(
    service: AdmissionService,
) -> None:
    decision = await service.decide(
        _request(unsatisfiedMandatoryCount=0, contradictionCount=0)
    )
    assert decision.shortCircuit is ShortCircuit.ALL_REQUIREMENTS_SATISFIED
    assert decision.deterministicAction is RecommendedAction.PREPARE_REPRESENTMENT
    assert decision.admit is False


async def test_short_circuit_order_matches_java(service: AdmissionService) -> None:
    """Past deadline wins even when everything else is satisfied."""
    decision = await service.decide(
        _request(
            deadlineAt=NOW - timedelta(hours=1),
            evidenceCount=0,
            unsatisfiedMandatoryCount=0,
            contradictionCount=0,
        )
    )
    assert decision.shortCircuit is ShortCircuit.PAST_DEADLINE


# --- budget gate ------------------------------------------------------------


class _StubBudget:
    def __init__(self, daily: bool = True, token: bool = True) -> None:
        self.daily = daily
        self.token = token
        self.refunded = False

    async def try_consume_daily_budget(self) -> bool:
        return self.daily

    async def try_consume_token(self) -> bool:
        return self.token

    async def refund(self) -> None:
        self.refunded = True


def _admissible() -> AdmissionRequest:
    return _request(
        disputeAmount=Money(amountMinor=10_000_000, currency="INR"),
        deadlineAt=NOW + timedelta(hours=2),
        contradictionCount=4,
        gapCount=4,
        deterministicConfidence=0.0,
    )


async def test_exhausted_daily_budget_refuses_admission() -> None:
    service = AdmissionService(budget=_StubBudget(daily=False))  # type: ignore[arg-type]
    decision = await service.decide(_admissible())
    assert decision.admit is False
    assert decision.shortCircuit is ShortCircuit.BUDGET_EXHAUSTED


async def test_rate_limit_refuses_and_refunds_the_daily_allowance() -> None:
    budget = _StubBudget(daily=True, token=False)
    service = AdmissionService(budget=budget)  # type: ignore[arg-type]
    decision = await service.decide(_admissible())
    assert decision.shortCircuit is ShortCircuit.RATE_LIMITED
    assert budget.refunded is True


# --- body shapes ------------------------------------------------------------


def test_admission_request_derived_from_an_investigation_context(
    contradictory_context: InvestigationContext,
) -> None:
    request = admission_request_from_payload(contradictory_context.to_wire())
    assert request.transactionId == "TX-0000123"
    assert request.evidenceCount == 3
    assert request.contradictionCount == 1
    assert request.unsatisfiedMandatoryCount == 0
    assert request.deterministicConfidence == 0.55


def test_compact_admission_request_is_used_as_supplied() -> None:
    request = admission_request_from_payload(
        {
            "caseId": "CASE-9",
            "contradictionCount": 3,
            "gapCount": 2,
            "evidenceCount": 4,
            "unsatisfiedMandatoryCount": 1,
            "deterministicConfidence": 0.25,
        }
    )
    assert request.caseId == "CASE-9"
    assert request.contradictionCount == 3
    assert request.deterministicConfidence == 0.25


def test_empty_context_derives_low_deterministic_confidence(
    empty_context: InvestigationContext,
) -> None:
    request = admission_request_from_payload(empty_context.to_wire())
    assert request.evidenceCount == 0
    assert request.deterministicConfidence == 0.20
