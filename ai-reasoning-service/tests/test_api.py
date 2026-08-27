"""HTTP surface tests with respx-mocked upstreams.

The app is built with an offline ``Settings`` and the mock provider, so these
tests exercise the real routers, the real dependency wiring and the real
lifespan without touching Redis, Gemini or the gateway.
"""

from __future__ import annotations

import json
from collections.abc import Iterator

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from pdei_ai.config import Settings
from pdei_ai.main import create_app
from pdei_ai.models.investigation import InvestigationContext

GATEWAY = "http://api-gateway-service:8080"


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    with TestClient(create_app(settings=settings)) as test_client:
        yield test_client


@pytest.fixture
def secured_client(settings: Settings) -> Iterator[TestClient]:
    secured = Settings(**{**settings.__dict__, "require_service_token": True})
    with TestClient(create_app(settings=secured)) as test_client:
        yield test_client


# --- probes -----------------------------------------------------------------


def test_health_is_dependency_free(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == "ai-reasoning-service"


@respx.mock
def test_ready_reports_the_active_provider_and_degraded_dependencies(
    client: TestClient,
) -> None:
    respx.get(f"{GATEWAY}/api/v1/health/ready").mock(return_value=httpx.Response(200))

    response = client.get("/ready")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["provider"] == "mock"
    assert body["checks"]["reasoner"]["status"] == "UP"
    assert body["checks"]["tools"]["status"] == "UP"
    assert body["checks"]["budget"]["status"] == "DISABLED"


@respx.mock
def test_ready_stays_up_when_the_gateway_is_unreachable(client: TestClient) -> None:
    """A tool-surface outage degrades quality, not capability."""
    respx.get(f"{GATEWAY}/api/v1/health/ready").mock(side_effect=httpx.ConnectError("refused"))

    body = client.get("/ready").json()

    assert body["status"] == "UP"
    assert body["checks"]["tools"]["status"] == "DEGRADED"


def test_metrics_exposes_the_contract_metric_names(client: TestClient) -> None:
    response = client.get("/metrics")
    assert response.status_code == 200
    text = response.text
    for name in (
        "pdei_ai_requests_total",
        "pdei_ai_admission_total",
        "pdei_ai_latency_seconds",
        "pdei_ai_unsupported_claims_total",
    ):
        assert name in text


# --- investigate ------------------------------------------------------------


def test_investigate_returns_a_contract_shaped_result(
    client: TestClient, defendable_context: InvestigationContext
) -> None:
    response = client.post("/v1/investigate", json=defendable_context.to_wire())

    assert response.status_code == 200
    body = response.json()
    assert body["investigationId"] == "INV-0000001"
    assert body["classification"] == "DEFENDABLE"
    assert body["recommendedAction"] == "PREPARE_REPRESENTMENT"
    assert 0.0 <= body["confidence"] <= 1.0
    assert set(body["supportingEvidence"]) == {"EV-1092", "EV-8821"}
    assert body["modelMetadata"]["provider"] == "mock"


def test_investigate_is_reproducible(
    client: TestClient, defendable_context: InvestigationContext
) -> None:
    payload = defendable_context.to_wire()
    first = client.post("/v1/investigate", json=payload).json()
    second = client.post("/v1/investigate", json=payload).json()
    assert first == second


def test_investigate_rejects_a_malformed_context(client: TestClient) -> None:
    response = client.post("/v1/investigate", json={"investigationId": "not-an-inv-id"})
    assert response.status_code == 422


def test_investigate_rejects_floating_point_money(
    client: TestClient, defendable_context: InvestigationContext
) -> None:
    payload = defendable_context.to_wire()
    payload["disputeAmount"] = {"amountMinor": 12999.99, "currency": "INR"}
    response = client.post("/v1/investigate", json=payload)
    assert response.status_code == 422


def test_investigate_stream_emits_sse_steps(
    client: TestClient, defendable_context: InvestigationContext
) -> None:
    response = client.post("/v1/investigate/stream", json=defendable_context.to_wire())

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    body = response.text
    for step in ("accepted", "context", "provider", "reasoning", "self_check", "result", "done"):
        assert f"event: {step}" in body

    result_frame = next(
        line
        for line in body.splitlines()
        if line.startswith("data:") and '"step": "result"' in line
    )
    payload = json.loads(result_frame[len("data:") :].strip())
    assert payload["detail"]["classification"] == "DEFENDABLE"


# --- admission --------------------------------------------------------------


def test_admission_score_accepts_an_investigation_context(
    client: TestClient, contradictory_context: InvestigationContext
) -> None:
    response = client.post("/v1/admission/score", json=contradictory_context.to_wire())

    assert response.status_code == 200
    body = response.json()
    assert {"admit", "priority", "reason"} <= set(body)
    assert isinstance(body["admit"], bool)
    assert 0 <= body["priority"] <= 100


def test_admission_score_accepts_a_compact_request(client: TestClient) -> None:
    response = client.post(
        "/v1/admission/score",
        json={
            "caseId": "CASE-1",
            "disputeAmount": {"amountMinor": 10_000_000, "currency": "INR"},
            "contradictionCount": 4,
            "gapCount": 4,
            "evidenceCount": 3,
            "unsatisfiedMandatoryCount": 2,
            "deterministicConfidence": 0.0,
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["admit"] is True
    assert body["shortCircuit"] == "NONE"


def test_admission_score_short_circuits_a_complete_case(
    client: TestClient, defendable_context: InvestigationContext
) -> None:
    body = client.post("/v1/admission/score", json=defendable_context.to_wire()).json()
    assert body["admit"] is False
    assert body["shortCircuit"] == "ALL_REQUIREMENTS_SATISFIED"
    assert body["deterministicAction"] == "PREPARE_REPRESENTMENT"


def test_admission_score_rejects_an_unusable_body(client: TestClient) -> None:
    response = client.post("/v1/admission/score", json={"nonsense": True})
    assert response.status_code == 422


# --- narrative --------------------------------------------------------------


def test_narrative_accepts_a_bare_context_and_returns_top_level_narrative(
    client: TestClient, defendable_context: InvestigationContext
) -> None:
    response = client.post("/v1/narrative", json=defendable_context.to_wire())

    assert response.status_code == 200
    body = response.json()
    assert body["narrative"]  # the field the Java client reads
    assert body["investigationId"] == "INV-0000001"
    assert all(
        citation["evidenceId"] in defendable_context.evidence_ids()
        for citation in body["citations"]
    )


def test_narrative_accepts_a_wrapped_request(
    client: TestClient, defendable_context: InvestigationContext
) -> None:
    response = client.post(
        "/v1/narrative",
        json={"context": defendable_context.to_wire(), "tone": "CONCISE", "maxWords": 120},
    )
    assert response.status_code == 200
    assert response.json()["narrative"]


# --- tools and providers ----------------------------------------------------


def test_tools_manifest_lists_ten_read_only_tools(client: TestClient) -> None:
    body = client.get("/v1/tools").json()

    assert body["count"] == 10
    assert body["readOnly"] is True
    assert body["basePath"] == "/api/v1/ai-tools"
    assert all(tool["method"] == "GET" for tool in body["tools"])
    assert body["authHeader"] == "X-PDEI-Service-Token"


def test_providers_reports_the_active_reasoner_and_chain(client: TestClient) -> None:
    body = client.get("/v1/providers").json()

    assert body["active"] == "mock"
    assert body["requested"] == "mock"
    assert "fallbackChain" in body
    assert body["budget"]["enabled"] is False
    assert any(report["provider"] == "mock" for report in body["health"])


def test_root_lists_the_contract_endpoints(client: TestClient) -> None:
    body = client.get("/").json()
    assert "POST /v1/investigate" in body["endpoints"]
    assert "GET /metrics" in body["endpoints"]


def test_openapi_exposes_exactly_the_contract_surface(client: TestClient) -> None:
    paths = client.get("/openapi.json").json()["paths"]
    assert set(paths) == {
        "/health",
        "/ready",
        "/v1/investigate",
        "/v1/investigate/stream",
        "/v1/admission/score",
        "/v1/narrative",
        "/v1/tools",
        "/v1/providers",
    }


# --- service token ----------------------------------------------------------


def test_service_token_is_required_when_enabled(
    secured_client: TestClient, defendable_context: InvestigationContext
) -> None:
    unauthorised = secured_client.post("/v1/investigate", json=defendable_context.to_wire())
    assert unauthorised.status_code == 401

    authorised = secured_client.post(
        "/v1/investigate",
        json=defendable_context.to_wire(),
        headers={"X-PDEI-Service-Token": "test-service-token"},
    )
    assert authorised.status_code == 200


def test_probes_never_require_a_token(secured_client: TestClient) -> None:
    assert secured_client.get("/health").status_code == 200
    assert secured_client.get("/metrics").status_code == 200


# --- provider override ------------------------------------------------------


def test_provider_override_header_selects_the_null_reasoner(
    client: TestClient, defendable_context: InvestigationContext
) -> None:
    body = client.post(
        "/v1/investigate",
        json=defendable_context.to_wire(),
        headers={"X-PDEI-Provider": "null"},
    ).json()

    assert body["modelMetadata"]["provider"] == "null"
    assert body["confidence"] == 0.0
    assert body["recommendedAction"] == "ESCALATE_TO_HUMAN"
