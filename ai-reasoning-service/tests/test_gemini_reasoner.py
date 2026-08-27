"""Gemini provider internals that can be tested without the SDK or an API key.

The response schema is derived from the Pydantic model, so this is where a
future model change that would break schema-constrained generation gets caught -
in CI, offline, rather than in a live call.
"""

from __future__ import annotations

import json

import pytest

from pdei_ai.models.investigation import InvestigationResult
from pdei_ai.reasoners.base import InvalidModelOutput, ReasonerUnavailable
from pdei_ai.reasoners.gemini import (
    INVESTIGATION_RESPONSE_SCHEMA,
    NARRATIVE_RESPONSE_SCHEMA,
    GeminiReasoner,
    _parse_json,
    _tenacity_retrying,
    _usage,
    build_response_schema,
)

# --- response schema --------------------------------------------------------


def test_schema_is_derived_from_the_pydantic_model() -> None:
    schema = INVESTIGATION_RESPONSE_SCHEMA
    model_fields = set(InvestigationResult.model_fields)
    schema_fields = set(schema["properties"])
    # Everything except the two fields the service owns.
    assert schema_fields == model_fields - {"investigationId", "modelMetadata"}


def test_service_owned_fields_are_not_asked_of_the_model() -> None:
    """An id echoed by a model is an id that can be echoed wrong."""
    assert "investigationId" not in INVESTIGATION_RESPONSE_SCHEMA["properties"]
    assert "modelMetadata" not in INVESTIGATION_RESPONSE_SCHEMA["properties"]


def test_schema_has_no_refs_or_defs_for_gemini() -> None:
    text = json.dumps(INVESTIGATION_RESPONSE_SCHEMA)
    assert "$ref" not in text
    assert "$defs" not in text
    assert "additionalProperties" not in text


def test_schema_carries_the_contract_enums() -> None:
    props = INVESTIGATION_RESPONSE_SCHEMA["properties"]
    assert props["classification"]["enum"] == [
        "DEFENDABLE",
        "WEAK",
        "INDEFENSIBLE",
        "INSUFFICIENT_EVIDENCE",
        "AMBIGUOUS",
    ]
    assert props["recommendedAction"]["enum"][0] == "PREPARE_REPRESENTMENT"


def test_schema_requires_the_fields_that_must_be_stated_not_defaulted() -> None:
    required = set(INVESTIGATION_RESPONSE_SCHEMA["required"])
    assert {
        "classification",
        "recommendedAction",
        "confidence",
        "supportingEvidence",
        "citations",
    } <= required


def test_optional_fields_become_nullable_rather_than_anyof() -> None:
    """Gemini does not accept anyOf; Optional[...] must collapse to nullable."""
    schema = build_response_schema(InvestigationResult)
    contradiction_items = schema["properties"]["contradictions"]["items"]
    left = contradiction_items["properties"]["left"]
    assert "anyOf" not in left
    assert left["type"] == "string"
    assert left["nullable"] is True


def test_narrative_schema_demands_citations() -> None:
    assert NARRATIVE_RESPONSE_SCHEMA["required"] == ["narrative", "citations"]


# --- output parsing ---------------------------------------------------------


def test_parse_json_accepts_plain_json() -> None:
    assert _parse_json('{"classification": "DEFENDABLE"}') == {"classification": "DEFENDABLE"}


def test_parse_json_strips_a_markdown_fence() -> None:
    fenced = '```json\n{"confidence": 0.9}\n```'
    assert _parse_json(fenced) == {"confidence": 0.9}


def test_parse_json_raises_invalid_model_output_with_the_raw_text() -> None:
    with pytest.raises(InvalidModelOutput) as exc:
        _parse_json("I think the case is defendable.")
    assert "defendable" in exc.value.raw_output


def test_parse_json_rejects_a_json_array() -> None:
    with pytest.raises(InvalidModelOutput, match="not an object"):
        _parse_json("[1, 2, 3]")


# --- misc -------------------------------------------------------------------


def test_backoff_schedule_starts_immediately_and_grows() -> None:
    schedule = _tenacity_retrying(4)
    assert len(schedule) == 4
    assert schedule[0] == 0.0
    assert schedule[-1] > schedule[1]


def test_usage_accounting_tolerates_a_missing_metadata_block() -> None:
    class _Response:
        usage_metadata = None

    assert _usage(_Response()) == {"promptTokens": 0, "completionTokens": 0}


def test_usage_accounting_reads_the_sdk_field_names() -> None:
    class _Meta:
        prompt_token_count = 120
        candidates_token_count = 45

    class _Response:
        usage_metadata = _Meta()

    assert _usage(_Response()) == {"promptTokens": 120, "completionTokens": 45}


def test_gemini_cannot_be_constructed_without_an_api_key() -> None:
    with pytest.raises(ReasonerUnavailable, match="GEMINI_API_KEY"):
        GeminiReasoner(api_key="")
