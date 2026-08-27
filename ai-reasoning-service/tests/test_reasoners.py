"""Reasoner behaviour: mock determinism, null abstention, registry fallback.

The determinism test is the load-bearing one. If it ever fails, every demo and
every downstream test that assumes reproducibility becomes unreliable.
"""

from __future__ import annotations

from pdei_ai.config import Settings
from pdei_ai.models.enums import InvestigationClassification, RecommendedAction
from pdei_ai.models.investigation import InvestigationContext
from pdei_ai.models.narrative import NarrativeRequest
from pdei_ai.reasoners.mock import MockReasoner, seed_for
from pdei_ai.reasoners.null import NullReasoner
from pdei_ai.reasoners.registry import ReasonerRegistry, build_registry

# --- determinism ------------------------------------------------------------


async def test_mock_reasoner_is_byte_identical_across_runs(
    defendable_context: InvestigationContext,
) -> None:
    first = await MockReasoner().investigate(defendable_context)
    second = await MockReasoner().investigate(defendable_context)
    assert first.model_dump_json() == second.model_dump_json()


async def test_mock_reasoner_latency_and_tokens_are_deterministic_too(
    defendable_context: InvestigationContext,
) -> None:
    """Even the metadata is a pure function of the context - see mock.py."""
    first = await MockReasoner().investigate(defendable_context)
    second = await MockReasoner().investigate(defendable_context)
    assert first.modelMetadata.latencyMs == second.modelMetadata.latencyMs
    assert first.modelMetadata.promptTokens == second.modelMetadata.promptTokens


async def test_different_investigations_get_different_confidence(
    defendable_context: InvestigationContext,
) -> None:
    other = defendable_context.model_copy(update={"investigationId": "INV-9999999"})
    first = await MockReasoner().investigate(defendable_context)
    second = await MockReasoner().investigate(other)
    assert first.confidence != second.confidence
    # ...but the classification is decided by the evidence, not by the seed.
    assert first.classification is second.classification


def test_seed_is_stable_and_not_python_hash_salted() -> None:
    assert seed_for("INV-0000001") == seed_for("INV-0000001")
    assert seed_for("INV-0000001") != seed_for("INV-0000002")


# --- decision tree ----------------------------------------------------------


async def test_clean_case_is_defendable(defendable_context: InvestigationContext) -> None:
    result = await MockReasoner().investigate(defendable_context)
    assert result.classification is InvestigationClassification.DEFENDABLE
    assert result.recommendedAction is RecommendedAction.PREPARE_REPRESENTMENT
    assert set(result.supportingEvidence) == {"EV-1092", "EV-8821"}
    assert result.confidence >= 0.90


async def test_contradictions_force_escalation(
    contradictory_context: InvestigationContext,
) -> None:
    result = await MockReasoner().investigate(contradictory_context)
    assert result.classification is InvestigationClassification.AMBIGUOUS
    assert result.recommendedAction is RecommendedAction.ESCALATE_TO_HUMAN
    assert result.confidence < 0.90


async def test_unsatisfied_mandatory_requirement_asks_for_more_evidence(
    gapped_context: InvestigationContext,
) -> None:
    result = await MockReasoner().investigate(gapped_context)
    assert result.classification is InvestigationClassification.INSUFFICIENT_EVIDENCE
    assert result.recommendedAction is RecommendedAction.GATHER_MORE_EVIDENCE
    assert [item.value for item in result.missingEvidence] == ["DELIVERY_PROOF"]


async def test_no_evidence_recommends_accepting_liability(
    empty_context: InvestigationContext,
) -> None:
    result = await MockReasoner().investigate(empty_context)
    assert result.recommendedAction is RecommendedAction.ACCEPT_LIABILITY
    assert result.supportingEvidence == []
    assert result.citations == []


async def test_mock_never_cites_evidence_absent_from_the_context(
    defendable_context: InvestigationContext,
) -> None:
    result = await MockReasoner().investigate(defendable_context)
    citable = defendable_context.evidence_ids()
    assert all(citation.evidenceId in citable for citation in result.citations)
    assert all(value in citable for value in result.supportingEvidence)


async def test_mock_narrative_is_deterministic_and_cited(
    defendable_context: InvestigationContext,
) -> None:
    request = NarrativeRequest(context=defendable_context)
    first = await MockReasoner().narrate(request)
    second = await MockReasoner().narrate(request)
    # generatedAt is a genuine wall-clock stamp and is excluded; everything the
    # model "decided" must be identical.
    excluded = {"generatedAt"}
    assert first.model_dump_json(exclude=excluded) == second.model_dump_json(exclude=excluded)
    assert first.narrative
    assert all(
        citation.evidenceId in defendable_context.evidence_ids() for citation in first.citations
    )


# --- null -------------------------------------------------------------------


async def test_null_reasoner_always_abstains(
    defendable_context: InvestigationContext,
) -> None:
    result = await NullReasoner().investigate(defendable_context)
    assert result.confidence == 0.0
    assert result.recommendedAction is RecommendedAction.ESCALATE_TO_HUMAN
    assert result.classification is InvestigationClassification.AMBIGUOUS
    assert result.supportingEvidence == []
    assert result.citations == []


async def test_null_reasoner_reports_healthy() -> None:
    """Abstention is a configuration, not a fault: falling through would defeat it."""
    health = await NullReasoner().health()
    assert health.healthy is True


# --- registry ---------------------------------------------------------------


def test_registry_selects_the_requested_provider(settings: Settings) -> None:
    registry = build_registry(settings)
    assert registry.active_name == "mock"
    assert isinstance(registry.active, MockReasoner)


def test_registry_falls_back_from_gemini_to_mock_without_an_api_key() -> None:
    settings = Settings(
        provider="gemini",
        fallback_chain=("gemini", "mock"),
        gemini_api_key="",
        redis_url="",
        tracing_enabled=False,
    )
    registry = build_registry(settings)
    assert registry.active_name == "mock"
    assert registry.chain[0] == "gemini"
    described = registry.describe()
    assert described["requested"] == "gemini"
    assert "gemini" in described["unavailable"]


def test_null_is_never_a_fallback_target() -> None:
    settings = Settings(
        provider="gemini",
        fallback_chain=("gemini", "null", "mock"),
        gemini_api_key="",
        redis_url="",
        tracing_enabled=False,
    )
    registry = build_registry(settings)
    assert "null" not in registry.chain
    assert registry.active_name == "mock"


def test_explicit_null_is_honoured_and_does_not_fall_through() -> None:
    settings = Settings(provider="null", redis_url="", tracing_enabled=False)
    registry = build_registry(settings)
    assert registry.active_name == "null"
    assert isinstance(registry.active, NullReasoner)


async def test_registry_health_lists_every_constructed_provider(settings: Settings) -> None:
    registry: ReasonerRegistry = build_registry(settings)
    reports = await registry.health()
    assert any(report.provider == "mock" and report.healthy for report in reports)
