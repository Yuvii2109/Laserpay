"""The unsupported-claim filter - the safety property this service exists for.

Non-negotiable rule 3: never invent evidence. These tests build results that a
badly behaved provider might return and assert that nothing unsupported survives
the service boundary.
"""

from __future__ import annotations

import pytest

from pdei_ai.config import Settings
from pdei_ai.models.enums import InvestigationClassification, RecommendedAction
from pdei_ai.models.investigation import (
    Citation,
    ContradictionRef,
    InvestigationContext,
    InvestigationResult,
    ModelMetadata,
)
from pdei_ai.reasoners.base import ReasonerError, ReasonerHealth
from pdei_ai.reasoners.registry import build_registry
from pdei_ai.services.investigation_service import (
    DEGRADED_CONFIDENCE_CEILING,
    InvestigationService,
)


def _service(settings: Settings, overrides: dict | None = None) -> InvestigationService:
    return InvestigationService(build_registry(settings, overrides=overrides))


def _result(
    context: InvestigationContext,
    supporting: list[str],
    citations: list[tuple[str, str]],
    classification: InvestigationClassification = InvestigationClassification.DEFENDABLE,
    confidence: float = 0.97,
    contradictions: list[ContradictionRef] | None = None,
) -> InvestigationResult:
    return InvestigationResult(
        investigationId=context.investigationId,
        classification=classification,
        confidence=confidence,
        supportingEvidence=supporting,
        contradictions=contradictions or [],
        reasoningSummary="provider summary",
        narrative="provider narrative",
        recommendedAction=RecommendedAction.PREPARE_REPRESENTMENT,
        citations=[Citation(claim=claim, evidenceId=eid) for claim, eid in citations],
        modelMetadata=ModelMetadata(provider="mock", model="pdei-mock-v1"),
    )


# --- the filter -------------------------------------------------------------


def test_supported_results_pass_through_untouched(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings)
    raw = _result(
        defendable_context,
        supporting=["EV-1092", "EV-8821"],
        citations=[("Parcel delivered", "EV-1092")],
    )
    checked, report = service.self_check(defendable_context, raw)

    assert report.clean
    assert checked.supportingEvidence == ["EV-1092", "EV-8821"]
    assert len(checked.citations) == 1
    assert checked.confidence == pytest.approx(0.97)
    assert checked.reasoningSummary == "provider summary"


def test_invented_citation_is_dropped(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings)
    raw = _result(
        defendable_context,
        supporting=["EV-1092"],
        citations=[
            ("Parcel delivered", "EV-1092"),
            ("Customer confirmed receipt by email", "EV-9999"),  # never existed
        ],
    )
    checked, report = service.self_check(defendable_context, raw)

    assert [c.evidenceId for c in checked.citations] == ["EV-1092"]
    assert len(report.droppedCitations) == 1
    assert report.droppedCitations[0].evidenceId == "EV-9999"
    assert "EV-9999" not in checked.model_dump_json()


def test_invented_supporting_evidence_is_dropped(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings)
    raw = _result(
        defendable_context,
        supporting=["EV-1092", "EV-4242"],
        citations=[("Parcel delivered", "EV-1092")],
    )
    checked, report = service.self_check(defendable_context, raw)

    assert checked.supportingEvidence == ["EV-1092"]
    assert report.droppedSupporting == ["EV-4242"]


def test_confidence_is_capped_once_anything_was_dropped(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings)
    raw = _result(
        defendable_context,
        supporting=["EV-1092", "EV-4242"],
        citations=[("Parcel delivered", "EV-1092")],
        confidence=0.99,
    )
    checked, report = service.self_check(defendable_context, raw)

    assert report.confidenceCapped
    assert checked.confidence == DEGRADED_CONFIDENCE_CEILING
    # ...and therefore below any plausible autoPrepareMinConfidence.
    assert checked.confidence < defendable_context.policyConstraints.autoPrepareMinConfidence


def test_wholly_unsupported_defendable_result_is_downgraded(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    """Every id invented: the conclusion has no basis left, so it cannot stand."""
    service = _service(settings)
    raw = _result(
        defendable_context,
        supporting=["EV-7777", "EV-8888"],
        citations=[("Delivered", "EV-7777")],
    )
    checked, report = service.self_check(defendable_context, raw)

    assert report.downgraded
    assert checked.classification is InvestigationClassification.INSUFFICIENT_EVIDENCE
    assert checked.recommendedAction is RecommendedAction.ESCALATE_TO_HUMAN
    assert checked.supportingEvidence == []
    assert checked.citations == []


def test_contradictions_referencing_unknown_evidence_are_dropped(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings)
    raw = _result(
        defendable_context,
        supporting=["EV-1092"],
        citations=[("Delivered", "EV-1092")],
        contradictions=[
            ContradictionRef(left="EV-1092", right="EV-8821", field="deliveredAt"),
            ContradictionRef(left="EV-1092", right="EV-0000", field="deliveredAt"),
        ],
    )
    checked, report = service.self_check(defendable_context, raw)

    assert len(checked.contradictions) == 1
    assert report.droppedContradictions == 1


def test_narrative_only_contradictions_survive(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    """A contradiction with no evidence pair references nothing, so it is not a claim."""
    service = _service(settings)
    raw = _result(
        defendable_context,
        supporting=["EV-1092"],
        citations=[("Delivered", "EV-1092")],
        contradictions=[ContradictionRef(detail="records disagree")],
    )
    checked, report = service.self_check(defendable_context, raw)

    assert len(checked.contradictions) == 1
    assert report.droppedContradictions == 0


def test_self_check_annotates_the_reasoning_summary(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    """A reviewer must be able to see that filtering happened."""
    service = _service(settings)
    raw = _result(
        defendable_context,
        supporting=["EV-1092"],
        citations=[("Delivered", "EV-9999")],
    )
    checked, _ = service.self_check(defendable_context, raw)
    assert "self-check" in checked.reasoningSummary


def test_context_with_no_evidence_makes_everything_unsupported(
    settings: Settings, empty_context: InvestigationContext
) -> None:
    service = _service(settings)
    raw = _result(
        empty_context,
        supporting=["EV-1"],
        citations=[("anything", "EV-1")],
    )
    checked, report = service.self_check(empty_context, raw)
    assert checked.supportingEvidence == []
    assert checked.citations == []
    assert report.downgraded


# --- end to end through a provider -----------------------------------------


class _LyingReasoner:
    """A provider that cites evidence that does not exist."""

    name = "mock"
    model = "lying-test-double"

    async def investigate(self, context: InvestigationContext) -> InvestigationResult:
        return _result(
            context,
            supporting=["EV-1092", "EV-6666"],
            citations=[("Delivered", "EV-1092"), ("Signed for", "EV-6666")],
        )

    async def narrate(self, request):  # pragma: no cover - unused here
        raise NotImplementedError

    async def health(self) -> ReasonerHealth:
        return ReasonerHealth(provider=self.name, model=self.model, healthy=True)


class _FailingReasoner:
    name = "mock"
    model = "failing-test-double"

    async def investigate(self, context: InvestigationContext) -> InvestigationResult:
        raise ReasonerError("provider exploded")

    async def narrate(self, request):  # pragma: no cover
        raise NotImplementedError

    async def health(self) -> ReasonerHealth:
        return ReasonerHealth(provider=self.name, model=self.model, healthy=False)


async def test_unsupported_claims_never_leave_the_service(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings, overrides={"mock": _LyingReasoner()})
    result = await service.investigate(defendable_context)
    assert "EV-6666" not in result.model_dump_json()
    assert result.supportingEvidence == ["EV-1092"]


async def test_provider_failure_propagates_for_the_route_to_handle(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings, overrides={"mock": _FailingReasoner()})
    with pytest.raises(ReasonerError):
        await service.investigate(defendable_context)


def test_deterministic_placeholder_is_fully_cited(
    defendable_context: InvestigationContext,
) -> None:
    placeholder = InvestigationService.deterministic_placeholder(
        defendable_context, "no provider available"
    )
    citable = defendable_context.evidence_ids()
    assert placeholder.modelMetadata.provider == "deterministic"
    assert placeholder.confidence == 0.0
    assert placeholder.recommendedAction is RecommendedAction.ESCALATE_TO_HUMAN
    assert all(c.evidenceId in citable for c in placeholder.citations)


# --- streaming --------------------------------------------------------------


async def test_stream_emits_the_expected_step_sequence(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings)
    steps = [step async for step in service.investigate_stream(defendable_context)]
    names = [step["step"] for step in steps]
    assert names == ["accepted", "context", "provider", "reasoning", "self_check", "result", "done"]
    assert steps[-2]["detail"]["classification"] == "DEFENDABLE"


async def test_stream_reports_the_filter_in_its_own_step(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings, overrides={"mock": _LyingReasoner()})
    steps = {step["step"]: step async for step in service.investigate_stream(defendable_context)}
    self_check = steps["self_check"]["detail"]
    assert self_check["clean"] is False
    assert self_check["droppedClaims"] == 1


async def test_stream_ends_with_an_error_step_when_the_provider_fails(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings, overrides={"mock": _FailingReasoner()})
    names = [
        step["step"] async for step in service.investigate_stream(defendable_context)
    ]
    assert names[-1] == "error"
