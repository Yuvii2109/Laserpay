"""Admission control types - platform contract 9.4.

Admission control answers one question: *is this case worth a model call?*

The authoritative answer lives in Java (``core.ai.AdmissionController``) because
a cost and safety control must not depend on the service it throttles. This
module mirrors the same formula so the Python side can score standalone - for
the simulator, for demos, and for ``POST /v1/admission/score``, which the Java
client treats as advisory only.
"""

from __future__ import annotations

from typing import Any

from pydantic import Field

from pdei_ai.models.common import Instant, Money, PdeiModel, utc_now
from pdei_ai.models.enums import DisputeReasonCode, RecommendedAction, _WireEnum


class ShortCircuit(_WireEnum):
    """Why a case did not reach the model.

    Mirrors ``com.laserpay.pdei.core.ai.ShortCircuit``. The first three are the
    deterministic short-circuits mandated by contract 9.4 - cases whose answer
    is already known and which MUST bypass AI entirely. The rest are throttles.
    """

    NONE = "NONE"
    ALL_REQUIREMENTS_SATISFIED = "ALL_REQUIREMENTS_SATISFIED"
    NO_EVIDENCE = "NO_EVIDENCE"
    PAST_DEADLINE = "PAST_DEADLINE"
    BELOW_PRIORITY_THRESHOLD = "BELOW_PRIORITY_THRESHOLD"
    RATE_LIMITED = "RATE_LIMITED"
    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"


class AdmissionRequest(PdeiModel):
    """Everything the priority formula needs.

    All of it is deterministic state the platform already computed - nothing
    here originates from a model. Field-identical to
    ``com.laserpay.pdei.core.ai.AdmissionRequest``.
    """

    caseId: str | None = None
    merchantId: str | None = None
    transactionId: str | None = None
    reasonCode: DisputeReasonCode | None = None
    disputeAmount: Money | None = None
    deadlineAt: Instant | None = None
    contradictionCount: int = Field(default=0, ge=0)
    gapCount: int = Field(default=0, ge=0)
    evidenceCount: int = Field(default=0, ge=0)
    unsatisfiedMandatoryCount: int = Field(default=0, ge=0)
    deterministicConfidence: float = Field(default=0.0, ge=0.0, le=1.0)
    now: Instant | None = None
    investigationId: str | None = None

    @property
    def all_mandatory_satisfied(self) -> bool:
        return self.unsatisfiedMandatoryCount <= 0

    @property
    def amount_minor(self) -> int:
        return 0 if self.disputeAmount is None else self.disputeAmount.amountMinor

    def past_deadline(self) -> bool:
        reference = self.now or utc_now()
        return self.deadlineAt is not None and reference > self.deadlineAt


class AdmissionDecision(PdeiModel):
    """Outcome of admission control.

    ``admit``, ``priority`` and ``reason`` are the three fields contract 8.6
    promises and the only three the Java ``AdmissionScore`` record reads. The
    rest are diagnostics for the observability page and for explaining the
    funnel; Jackson ignores unknown fields, so shipping them is free.
    """

    admit: bool
    priority: int = Field(ge=0, le=100)
    reason: str
    shortCircuit: ShortCircuit = ShortCircuit.NONE
    deterministicAction: RecommendedAction | None = None
    financialImpact: float = Field(default=0.0, ge=0.0, le=1.0)
    deadlineUrgency: float = Field(default=0.0, ge=0.0, le=1.0)
    ambiguityScore: float = Field(default=0.0, ge=0.0, le=1.0)
    deterministicConfidence: float = Field(default=0.0, ge=0.0, le=1.0)
    threshold: int = Field(default=55, ge=0, le=100)
    evaluatedAt: Instant = Field(default_factory=utc_now)

    @property
    def resolved_deterministically(self) -> bool:
        return self.deterministicAction is not None


def admission_request_from_payload(payload: dict[str, Any]) -> AdmissionRequest:
    """Build an ``AdmissionRequest`` from either shape posted to ``/v1/admission/score``.

    ``HttpAiReasoningClient`` on the Java side posts a full
    ``InvestigationContext``; the simulator and the tests post a compact
    ``AdmissionRequest``. Rather than two endpoints, the route accepts both and
    this function normalises them. Detection is structural: only the compact
    form carries the pre-counted fields.
    """
    from pdei_ai.models.investigation import InvestigationContext  # local: avoids a cycle

    counted_keys = {
        "contradictionCount",
        "gapCount",
        "evidenceCount",
        "unsatisfiedMandatoryCount",
    }
    if counted_keys & set(payload):
        return AdmissionRequest.model_validate(payload)

    context = InvestigationContext.model_validate(payload)
    return from_context(context)


def from_context(context: Any) -> AdmissionRequest:
    """Derive the counted admission inputs from a curated investigation context.

    ``deterministicConfidence`` is not supplied by the caller in this shape, so
    it is derived the same way the Java ``DeterministicInvestigator`` grades
    itself: full confidence when every mandatory requirement is satisfied and
    nothing contradicts, partial when the picture is incomplete, near-zero when
    there is no evidence at all. A confident deterministic path lowers priority,
    which is exactly the intent of the ``0.15 * (1 - confidence)`` term.
    """
    unsatisfied = len(context.unsatisfied_mandatory())
    contradiction_count = len(context.contradictions)
    evidence_count = len(context.evidence)

    if evidence_count == 0:
        deterministic_confidence = 0.20
    elif unsatisfied == 0 and contradiction_count == 0:
        deterministic_confidence = 1.0
    else:
        deterministic_confidence = 0.55

    return AdmissionRequest(
        caseId=context.caseId,
        merchantId=context.merchantId,
        transactionId=context.transactionId,
        reasonCode=context.reasonCode,
        disputeAmount=context.disputeAmount,
        deadlineAt=context.deadlineAt,
        contradictionCount=contradiction_count,
        gapCount=len(context.gaps),
        evidenceCount=evidence_count,
        unsatisfiedMandatoryCount=unsatisfied,
        deterministicConfidence=deterministic_confidence,
        now=utc_now(),
        investigationId=context.investigationId,
    )
