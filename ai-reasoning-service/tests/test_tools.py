"""The tool boundary: closed registry, GET only, and no path escapes.

The two refusals that matter most are asserted at the executor, not just at the
registry, and both are asserted to happen *before any HTTP request is made* -
a recording client double proves the call never left the process.
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import httpx
import pytest
import respx

from pdei_ai.tools import registry as tool_registry
from pdei_ai.tools.client import AiToolsClient, ToolPathError, ToolTransportError
from pdei_ai.tools.executor import ToolExecutor
from pdei_ai.tools.registry import (
    TOOLS_BASE_PATH,
    ToolArgumentError,
    ToolParameter,
    ToolSpec,
    ToolSpecError,
    UnknownToolError,
    all_specs,
    gemini_function_declarations,
    manifest,
)

GATEWAY = "http://api-gateway-service:8080"


class _RecordingClient:
    """Stands in for AiToolsClient; records every call that reaches it."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, str] | None]] = []

    async def get(self, path: str, params: dict[str, str] | None = None) -> Any:
        self.calls.append((path, params))
        return {"ok": True, "path": path}


# --- registry shape ---------------------------------------------------------


def test_registry_holds_exactly_the_ten_contract_tools() -> None:
    names = [spec.name for spec in all_specs()]
    assert names == [
        "getTransaction",
        "getOrder",
        "getShipment",
        "getRefund",
        "getEvidence",
        "findRelatedEvidence",
        "findContradictions",
        "getApplicablePolicy",
        "getEvidenceRequirements",
        "getTimeline",
    ]


def test_every_tool_is_get_and_lives_under_the_ai_tools_prefix() -> None:
    for spec in all_specs():
        assert spec.method == "GET"
        assert spec.path.startswith(TOOLS_BASE_PATH + "/")


def test_tool_paths_match_contract_8_6() -> None:
    by_name = {spec.name: spec.path for spec in all_specs()}
    assert by_name["getTransaction"] == "/api/v1/ai-tools/transaction/{id}"
    assert by_name["getOrder"] == "/api/v1/ai-tools/order/{id}"
    assert by_name["getShipment"] == "/api/v1/ai-tools/shipment/{id}"
    assert by_name["getRefund"] == "/api/v1/ai-tools/refund/{id}"
    assert by_name["getEvidence"] == "/api/v1/ai-tools/evidence/{id}"
    assert by_name["findRelatedEvidence"] == "/api/v1/ai-tools/evidence/related"
    assert by_name["findContradictions"] == "/api/v1/ai-tools/contradictions"
    assert by_name["getApplicablePolicy"] == "/api/v1/ai-tools/policy/applicable"
    assert by_name["getEvidenceRequirements"] == "/api/v1/ai-tools/requirements"
    assert by_name["getTimeline"] == "/api/v1/ai-tools/timeline/{transactionId}"


def test_a_non_get_tool_cannot_be_declared() -> None:
    with pytest.raises(ToolSpecError, match="read-only"):
        ToolSpec(
            name="submitDispute",
            description="write something",
            path=f"{TOOLS_BASE_PATH}/submit",
            returns="void",
            method="POST",
        )


def test_a_tool_path_outside_the_prefix_cannot_be_declared() -> None:
    with pytest.raises(ToolSpecError, match="escapes"):
        ToolSpec(
            name="getEverything",
            description="reach outside the tool surface",
            path="/api/v1/evidence/{id}",
            path_params=(ToolParameter("id", "id"),),
            returns="EvidenceView",
        )


def test_manifest_and_function_declarations_come_from_the_same_specs() -> None:
    payload = manifest()
    assert payload["count"] == 10
    assert payload["readOnly"] is True
    assert {entry["name"] for entry in payload["tools"]} == {
        declaration["name"] for declaration in gemini_function_declarations()
    }
    assert all(entry["method"] == "GET" for entry in payload["tools"])


def test_function_declarations_describe_required_arguments() -> None:
    declarations = {d["name"]: d for d in gemini_function_declarations()}
    assert declarations["getApplicablePolicy"]["parameters"]["required"] == [
        "merchantId",
        "reasonCode",
    ]


# --- argument handling ------------------------------------------------------


def test_build_request_substitutes_path_and_query() -> None:
    path, query = tool_registry.get("getTimeline").build_request({"transactionId": "TX-1"})
    assert path == "/api/v1/ai-tools/timeline/TX-1"
    assert query == {}

    path, query = tool_registry.get("findRelatedEvidence").build_request({"transactionId": "TX-1"})
    assert path == "/api/v1/ai-tools/evidence/related"
    assert query == {"transactionId": "TX-1"}


def test_missing_required_argument_is_refused() -> None:
    with pytest.raises(ToolArgumentError, match="requires argument"):
        tool_registry.get("getTransaction").build_request({})


def test_unknown_argument_is_refused() -> None:
    with pytest.raises(ToolArgumentError, match="unknown arguments"):
        tool_registry.get("getTransaction").build_request({"id": "TX-1", "limit": "500"})


@pytest.mark.parametrize(
    "hostile",
    ["../../../admin", "TX-1/../../evidence", "TX 1", "TX-1?admin=true", "TX-1#frag"],
)
def test_path_traversal_and_injection_attempts_are_refused(hostile: str) -> None:
    with pytest.raises(ToolArgumentError):
        tool_registry.get("getTransaction").build_request({"id": hostile})


# --- executor refusals ------------------------------------------------------


async def test_executor_refuses_an_unknown_tool_without_calling_upstream() -> None:
    client = _RecordingClient()
    executor = ToolExecutor(client)  # type: ignore[arg-type]

    result = await executor.execute("deleteEvidence", {"id": "EV-1"})

    assert result.ok is False
    assert "unknown tool" in (result.error or "")
    assert client.calls == []
    assert executor.calls_made == 0


async def test_executor_refuses_a_non_get_tool_at_dispatch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Second guard: even if a POST spec existed, dispatch must refuse it."""
    client = _RecordingClient()
    executor = ToolExecutor(client)  # type: ignore[arg-type]

    rogue = SimpleNamespace(
        name="submitRepresentment",
        method="POST",
        path=f"{TOOLS_BASE_PATH}/submit",
        build_request=lambda _args: (f"{TOOLS_BASE_PATH}/submit", {}),
    )
    monkeypatch.setitem(tool_registry.TOOLS, "submitRepresentment", rogue)  # type: ignore[arg-type]

    result = await executor.execute("submitRepresentment", {})

    assert result.ok is False
    assert "read-only" in (result.error or "")
    assert client.calls == []


async def test_executor_enforces_the_call_budget() -> None:
    client = _RecordingClient()
    executor = ToolExecutor(client, max_calls=2)  # type: ignore[arg-type]

    for _ in range(2):
        assert (await executor.execute("getTransaction", {"id": "TX-1"})).ok
    third = await executor.execute("getTransaction", {"id": "TX-1"})

    assert third.ok is False
    assert "budget exhausted" in (third.error or "")
    assert len(client.calls) == 2


async def test_executor_refuses_bad_arguments_without_calling_upstream() -> None:
    client = _RecordingClient()
    executor = ToolExecutor(client)  # type: ignore[arg-type]

    result = await executor.execute("getEvidence", {"wrongName": "EV-1"})

    assert result.ok is False
    assert client.calls == []


async def test_executor_without_a_client_degrades_to_context_only() -> None:
    executor = ToolExecutor(None)
    result = await executor.execute("getTransaction", {"id": "TX-1"})
    assert result.ok is False
    assert "disabled" in (result.error or "")


# --- the client against a mocked gateway ------------------------------------


@respx.mock
async def test_client_calls_the_gateway_with_the_service_token() -> None:
    route = respx.get(f"{GATEWAY}/api/v1/ai-tools/transaction/TX-1").mock(
        return_value=httpx.Response(200, json={"transactionId": "TX-1", "status": "CAPTURED"})
    )
    async with AiToolsClient(GATEWAY, "test-token", max_retries=0) as client:
        data = await client.get("/api/v1/ai-tools/transaction/TX-1")

    assert data["status"] == "CAPTURED"
    assert route.calls.last.request.headers["X-PDEI-Service-Token"] == "test-token"


@respx.mock
async def test_client_refuses_a_path_outside_the_tool_prefix() -> None:
    async with AiToolsClient(GATEWAY, "test-token") as client:
        with pytest.raises(ToolPathError):
            await client.get("/api/v1/evidence/EV-1")


@respx.mock
async def test_client_does_not_retry_a_404() -> None:
    route = respx.get(f"{GATEWAY}/api/v1/ai-tools/evidence/EV-404").mock(
        return_value=httpx.Response(404)
    )
    async with AiToolsClient(GATEWAY, "t", max_retries=3) as client:
        with pytest.raises(ToolTransportError) as exc:
            await client.get("/api/v1/ai-tools/evidence/EV-404")

    assert exc.value.status_code == 404
    assert route.call_count == 1


@respx.mock
async def test_client_retries_a_503_then_succeeds() -> None:
    route = respx.get(f"{GATEWAY}/api/v1/ai-tools/contradictions").mock(
        side_effect=[httpx.Response(503), httpx.Response(200, json=[])]
    )
    async with AiToolsClient(GATEWAY, "t", max_retries=2) as client:
        data = await client.get("/api/v1/ai-tools/contradictions", {"transactionId": "TX-1"})

    assert data == []
    assert route.call_count == 2


@respx.mock
async def test_executor_end_to_end_against_a_mocked_gateway() -> None:
    respx.get(f"{GATEWAY}/api/v1/ai-tools/evidence/related").mock(
        return_value=httpx.Response(200, json=[{"evidenceId": "EV-1092", "type": "DELIVERY_PROOF"}])
    )
    async with AiToolsClient(GATEWAY, "t", max_retries=0) as client:
        executor = ToolExecutor(client, max_calls=4)
        result = await executor.execute("findRelatedEvidence", {"transactionId": "TX-1"})

    assert result.ok
    assert result.data[0]["evidenceId"] == "EV-1092"
    assert result.to_model_payload()["ok"] is True


@respx.mock
async def test_executor_reports_upstream_failure_as_a_tool_error_not_a_crash() -> None:
    respx.get(f"{GATEWAY}/api/v1/ai-tools/order/ORD-1").mock(return_value=httpx.Response(500))
    async with AiToolsClient(GATEWAY, "t", max_retries=0) as client:
        executor = ToolExecutor(client)
        result = await executor.execute("getOrder", {"id": "ORD-1"})

    assert result.ok is False
    assert "500" in (result.error or "")


def test_unknown_tool_error_message_names_the_closed_set() -> None:
    assert "closed set of 10" in str(UnknownToolError("nope"))
