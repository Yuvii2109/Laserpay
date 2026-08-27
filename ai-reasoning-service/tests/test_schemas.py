"""The committed JSON Schemas must match the models and accept real payloads.

``schemas/ai/*.schema.json`` is the referee when Java, Python and TypeScript
disagree (SHARED-LIBRARY-API section 4). A referee that has drifted from the
models is worse than no referee, so ``--check`` runs here as a test.
"""

from __future__ import annotations

import json
from pathlib import Path

import jsonschema
import pytest

from pdei_ai.models.enums import InvestigationClassification, RecommendedAction
from pdei_ai.models.investigation import (
    Citation,
    InvestigationContext,
    InvestigationResult,
    ModelMetadata,
)
from pdei_ai.schemas_export import default_output_dir, export

SCHEMA_FILES = (
    "investigation-result.schema.json",
    "investigation-context.schema.json",
    "admission-decision.schema.json",
)


@pytest.fixture(scope="module")
def schema_dir() -> Path:
    return default_output_dir()


def load(schema_dir: Path, name: str) -> dict:
    return json.loads((schema_dir / name).read_text(encoding="utf-8"))


@pytest.mark.parametrize("name", SCHEMA_FILES)
def test_schema_files_are_committed_and_valid(schema_dir: Path, name: str) -> None:
    schema = load(schema_dir, name)
    jsonschema.Draft202012Validator.check_schema(schema)
    assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    assert schema["$id"].endswith(name)


def test_committed_schemas_are_not_stale() -> None:
    """Regenerate with ``uv run pdei-ai-export-schemas`` when this fails."""
    assert export(check=True) == 0


def test_contract_9_2_example_validates(schema_dir: Path) -> None:
    """The literal example from platform contract 9.2."""
    schema = load(schema_dir, "investigation-result.schema.json")
    example = {
        "investigationId": "INV-0000001",
        "classification": "DEFENDABLE",
        "confidence": 0.973,
        "supportingEvidence": ["EV-1092", "EV-8821"],
        "missingEvidence": [],
        "contradictions": [],
        "reasoningSummary": "Delivery is supported by the carrier record.",
        "narrative": "The parcel was delivered.",
        "recommendedAction": "PREPARE_REPRESENTMENT",
        "citations": [{"claim": "Delivered", "evidenceId": "EV-1092"}],
        "modelMetadata": {
            "provider": "gemini",
            "model": "gemini-3.5-flash-lite",
            "promptTokens": 0,
            "completionTokens": 0,
            "latencyMs": 0,
            "attempt": 1,
        },
    }
    jsonschema.validate(example, schema)
    assert InvestigationResult.model_validate(example).confidence == 0.973


def test_contract_9_1_example_validates(schema_dir: Path) -> None:
    """The literal example from platform contract 9.1."""
    schema = load(schema_dir, "investigation-context.schema.json")
    example = {
        "investigationId": "INV-0000001",
        "caseId": "CASE-0000001",
        "disputeId": "DSP-0000001",
        "merchantId": "MER-0001",
        "transactionId": "TX-0000123",
        "reasonCode": "GOODS_NOT_RECEIVED",
        "disputeAmount": {"amountMinor": 1299900, "currency": "INR"},
        "deadlineAt": "2026-09-10T00:00:00Z",
        "transactionSummary": {},
        "evidence": [
            {
                "evidenceId": "EV-1092",
                "type": "DELIVERY_PROOF",
                "status": "ACTIVE",
                "sha256": "abc",
                "createdAt": "2026-08-20T10:00:00Z",
                "summary": "carrier proof of delivery",
                "version": 2,
            }
        ],
        "requirements": [{"type": "DELIVERY_PROOF", "strength": "MANDATORY", "satisfied": True}],
        "gaps": [
            {"type": "MISSING", "evidenceType": "CUSTOMER_COMMUNICATION", "severity": "MEDIUM"}
        ],
        "contradictions": [
            {"left": "EV-1092", "right": "EV-8821", "field": "deliveredAt", "detail": "x"}
        ],
        "policyConstraints": {
            "autoPrepareMinConfidence": 0.90,
            "maxContradictions": 0,
            "prohibitedEvidenceTypes": [],
        },
        "timeline": [
            {"at": "2026-08-20T10:00:00Z", "eventType": "ShipmentDelivered", "summary": "ok"}
        ],
        "historicalContext": {"merchantWinRate": 0.71, "similarCases": 14},
    }
    jsonschema.validate(example, schema)
    assert InvestigationContext.model_validate(example).transactionId == "TX-0000123"


def test_schema_rejects_a_non_ev_supporting_evidence_id(schema_dir: Path) -> None:
    schema = load(schema_dir, "investigation-result.schema.json")
    bad = {
        "investigationId": "INV-1",
        "classification": "DEFENDABLE",
        "confidence": 0.9,
        "supportingEvidence": ["1092"],
        "recommendedAction": "PREPARE_REPRESENTMENT",
        "modelMetadata": {"provider": "mock", "model": "m"},
    }
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(bad, schema)


def test_schema_rejects_out_of_range_confidence(schema_dir: Path) -> None:
    schema = load(schema_dir, "investigation-result.schema.json")
    bad = {
        "investigationId": "INV-1",
        "classification": "DEFENDABLE",
        "confidence": 97.3,
        "recommendedAction": "PREPARE_REPRESENTMENT",
        "modelMetadata": {"provider": "mock", "model": "m"},
    }
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(bad, schema)


def test_result_produced_by_the_models_validates_against_the_schema(schema_dir: Path) -> None:
    schema = load(schema_dir, "investigation-result.schema.json")
    result = InvestigationResult(
        investigationId="INV-42",
        classification=InvestigationClassification.WEAK,
        confidence=0.61,
        supportingEvidence=["EV-1"],
        citations=[Citation(claim="on file", evidenceId="EV-1")],
        recommendedAction=RecommendedAction.GATHER_MORE_EVIDENCE,
        modelMetadata=ModelMetadata(provider="mock", model="pdei-mock-v1"),
    )
    jsonschema.validate(result.to_wire(), schema)
