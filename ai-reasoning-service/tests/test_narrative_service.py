"""Narrative safety: unsupported citations dropped, unsupported ids redacted.

Prose is where a fabricated fact hides from a schema validator, so the narrative
gets a second filter that the structured path does not need: the text itself is
scanned for evidence references.
"""

from __future__ import annotations

from pdei_ai.config import Settings
from pdei_ai.models.investigation import Citation, InvestigationContext, ModelMetadata
from pdei_ai.models.narrative import NarrativeRequest, NarrativeResult
from pdei_ai.reasoners.base import ReasonerHealth
from pdei_ai.reasoners.registry import build_registry
from pdei_ai.services.narrative_service import REDACTION_MARKER, NarrativeService


def _service(settings: Settings, overrides: dict | None = None) -> NarrativeService:
    return NarrativeService(build_registry(settings, overrides=overrides))


def _narrative(context: InvestigationContext, text: str, citations: list[tuple[str, str]]):
    return NarrativeResult(
        investigationId=context.investigationId,
        narrative=text,
        citations=[Citation(claim=claim, evidenceId=eid) for claim, eid in citations],
        evidenceIds=[eid for _, eid in citations],
        modelMetadata=ModelMetadata(provider="mock", model="pdei-mock-v1"),
    )


class _FabricatingReasoner:
    name = "mock"
    model = "fabricating-test-double"

    async def investigate(self, context):  # pragma: no cover - unused here
        raise NotImplementedError

    async def narrate(self, request: NarrativeRequest) -> NarrativeResult:
        return _narrative(
            request.context,
            "The parcel was delivered per EV-1092 and signed for per EV-9999.",
            [("Delivered", "EV-1092"), ("Signed for", "EV-9999")],
        )

    async def health(self) -> ReasonerHealth:
        return ReasonerHealth(provider=self.name, model=self.model, healthy=True)


def test_supported_narrative_is_untouched(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings)
    raw = _narrative(
        defendable_context,
        "The parcel was delivered per EV-1092.",
        [("Delivered", "EV-1092")],
    )
    checked = service.enforce_citation_discipline(defendable_context, raw)

    assert checked.narrative == raw.narrative
    assert checked.droppedClaims == 0
    assert checked.redactedReferences == 0
    assert checked.evidenceIds == ["EV-1092"]


def test_unsupported_citation_is_dropped(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings)
    raw = _narrative(
        defendable_context,
        "The parcel was delivered.",
        [("Delivered", "EV-1092"), ("Signed for", "EV-9999")],
    )
    checked = service.enforce_citation_discipline(defendable_context, raw)

    assert checked.droppedClaims == 1
    assert [c.evidenceId for c in checked.citations] == ["EV-1092"]


def test_unsupported_reference_in_the_prose_is_redacted_visibly(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings)
    raw = _narrative(
        defendable_context,
        "Delivered per EV-1092 and signed for per EV-9999.",
        [("Delivered", "EV-1092")],
    )
    checked = service.enforce_citation_discipline(defendable_context, raw)

    assert "EV-9999" not in checked.narrative
    assert REDACTION_MARKER in checked.narrative
    assert "EV-1092" in checked.narrative
    assert checked.redactedReferences == 1


def test_redaction_helper_counts_every_unsupported_token() -> None:
    text, count = NarrativeService.redact_unsupported_references(
        "EV-1 and EV-2 and EV-1 again", {"EV-1"}
    )
    assert count == 1
    assert text.count("EV-1") == 2
    assert REDACTION_MARKER in text


def test_empty_narrative_is_handled() -> None:
    text, count = NarrativeService.redact_unsupported_references("", {"EV-1"})
    assert text == ""
    assert count == 0


async def test_fabricated_ids_never_reach_a_reviewer(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    service = _service(settings, overrides={"mock": _FabricatingReasoner()})
    result = await service.narrate(NarrativeRequest(context=defendable_context))

    assert "EV-9999" not in result.model_dump_json()
    assert result.droppedClaims == 1
    assert result.redactedReferences == 1


async def test_mock_narrative_survives_the_filter_unchanged(
    settings: Settings, defendable_context: InvestigationContext
) -> None:
    """The mock cannot fabricate, so nothing should be filtered."""
    service = _service(settings)
    result = await service.narrate(NarrativeRequest(context=defendable_context))
    assert result.droppedClaims == 0
    assert result.redactedReferences == 0
    assert result.narrative
