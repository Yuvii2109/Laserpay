"""``GET /v1/tools`` (platform contract 8.6) - the tool manifest.

This is the manifest an operator, the frontend and a reviewer read to answer the
question "what can the model actually do?" The answer is: ten GET requests
against one transaction, and nothing else.

The manifest is generated from ``pdei_ai.tools.registry``, the same source the
executor dispatches from and the same source the Gemini function declarations
come from. It cannot describe a capability the executor does not have, or omit
one that it does.

There is deliberately **no invoke endpoint here**. Tools are called by the model
through ``ToolExecutor`` during an investigation, never by an HTTP caller.
Exposing a generic invoke route would turn a curated, budgeted, audited tool
layer into an open proxy for the gateway.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

from pdei_ai.api.deps import SettingsDep, ToolExecutorDep, require_service_token
from pdei_ai.tools.registry import manifest

router = APIRouter(prefix="/v1", tags=["tools"], dependencies=[Depends(require_service_token)])


@router.get("/tools", summary="Read-only tool manifest exposed to the model")
async def tools(settings: SettingsDep, executor: ToolExecutorDep) -> dict[str, Any]:
    payload = manifest()
    payload["enabled"] = bool(
        settings.tools_enabled and executor is not None and executor.available
    )
    payload["maxCallsPerInvestigation"] = settings.max_tool_calls
    payload["upstream"] = settings.api_base_url
    payload["timeoutSeconds"] = settings.tool_timeout_seconds
    payload["enforcement"] = {
        "unknownToolNames": "refused by the executor before any HTTP request",
        "nonGetMethods": "rejected at ToolSpec construction and again at dispatch",
        "pathPrefix": "every call is constrained to /api/v1/ai-tools/",
        "redirects": "not followed, so a 3xx cannot walk a call out of the prefix",
    }
    return payload
