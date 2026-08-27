"""Dispatches model-requested tool calls - the enforcement point of the tool boundary.

Everything the model asks for passes through ``ToolExecutor.execute``. Three
refusals happen here, in code, before any network call:

1. **Unknown tool name.** The registry is a closed set of ten. An unrecognised
   name is refused outright; it is never forwarded upstream, never guessed at,
   never fuzzy-matched.
2. **Non-GET method.** A spec that somehow declares anything but GET is refused
   even though ``ToolSpec`` already rejects it at construction. Two independent
   checks, because this is the rule that keeps the model unable to write.
3. **Malformed or unknown arguments.** Identifiers only, required arguments
   present, nothing extra.

A refusal is returned to the model as a normal tool result carrying an error,
not raised as an exception. The model must be able to learn that a tool does not
exist and continue reasoning; crashing the investigation would turn a model
mistake into a platform outage.
"""

from __future__ import annotations

import time
from typing import Any

from pdei_ai.models.common import PdeiModel
from pdei_ai.observability.logging import get_logger
from pdei_ai.observability.metrics import record_tool_call, record_tool_rejected
from pdei_ai.tools.client import AiToolsClient, ToolPathError, ToolTransportError
from pdei_ai.tools.registry import (
    ToolArgumentError,
    ToolSpec,
    UnknownToolError,
    all_specs,
    get,
    has,
)

log = get_logger(__name__)

ALLOWED_METHOD = "GET"


class ToolCallResult(PdeiModel):
    """Outcome of one tool call, in the shape handed back to the model."""

    tool: str
    ok: bool
    arguments: dict[str, Any] = {}
    data: Any = None
    error: str | None = None
    latencyMs: int = 0

    def to_model_payload(self) -> dict[str, Any]:
        """Compact form fed back into the conversation as a function response."""
        if self.ok:
            return {"tool": self.tool, "ok": True, "data": self.data}
        return {"tool": self.tool, "ok": False, "error": self.error}


class ToolExecutor:
    """Validates, dispatches and records read-only tool calls."""

    def __init__(self, client: AiToolsClient | None, max_calls: int = 8) -> None:
        self._client = client
        self.max_calls = max(0, max_calls)
        self._calls_made = 0

    @property
    def available(self) -> bool:
        """False when no client is configured - the model then works from context alone."""
        return self._client is not None

    @property
    def calls_made(self) -> int:
        return self._calls_made

    def reset(self) -> None:
        """Reset the per-investigation call budget."""
        self._calls_made = 0

    async def execute(self, name: str, arguments: dict[str, Any] | None = None) -> ToolCallResult:
        """Run one tool call. Never raises for model-caused problems."""
        arguments = dict(arguments or {})
        started = time.perf_counter()

        # --- 1. closed set -------------------------------------------------
        if not has(name):
            record_tool_rejected("unknown_tool")
            log.warning("refused unknown tool", tool=name, known=[s.name for s in all_specs()])
            return ToolCallResult(
                tool=name,
                ok=False,
                arguments=arguments,
                error=str(UnknownToolError(name)),
            )

        spec: ToolSpec = get(name)

        # --- 2. read-only, checked again at dispatch -----------------------
        if spec.method != ALLOWED_METHOD:  # pragma: no cover - ToolSpec refuses this first
            record_tool_rejected("non_get_method")
            log.error("refused non-GET tool", tool=name, method=spec.method)
            return ToolCallResult(
                tool=name,
                ok=False,
                arguments=arguments,
                error=(
                    f"tool {name!r} declares method {spec.method!r}; the AI tool layer is "
                    "read-only and only GET is dispatched"
                ),
            )

        # --- 3. budget ------------------------------------------------------
        if self._calls_made >= self.max_calls:
            record_tool_rejected("budget_exhausted")
            return ToolCallResult(
                tool=name,
                ok=False,
                arguments=arguments,
                error=f"tool call budget exhausted ({self.max_calls} calls per investigation)",
            )

        if self._client is None:
            record_tool_rejected("no_client")
            return ToolCallResult(
                tool=name,
                ok=False,
                arguments=arguments,
                error="tool layer is disabled; answer from the supplied context only",
            )

        # --- 4. arguments ---------------------------------------------------
        try:
            path, query = spec.build_request(arguments)
        except (ToolArgumentError, ValueError) as exc:
            record_tool_rejected("bad_arguments")
            return ToolCallResult(tool=name, ok=False, arguments=arguments, error=str(exc))

        # --- 5. dispatch ----------------------------------------------------
        self._calls_made += 1
        try:
            data = await self._client.get(path, query)
        except ToolPathError as exc:  # pragma: no cover - defence in depth
            record_tool_rejected("path_escape")
            log.error("tool path escaped the ai-tools prefix", tool=name, error=str(exc))
            return ToolCallResult(tool=name, ok=False, arguments=arguments, error=str(exc))
        except ToolTransportError as exc:
            elapsed = time.perf_counter() - started
            outcome = "not_found" if exc.status_code == 404 else "error"
            record_tool_call(name, outcome, elapsed)
            return ToolCallResult(
                tool=name,
                ok=False,
                arguments=arguments,
                error=str(exc),
                latencyMs=int(elapsed * 1000),
            )

        elapsed = time.perf_counter() - started
        record_tool_call(name, "success", elapsed)
        return ToolCallResult(
            tool=name,
            ok=True,
            arguments=arguments,
            data=data,
            latencyMs=int(elapsed * 1000),
        )

    async def execute_many(self, calls: list[tuple[str, dict[str, Any]]]) -> list[ToolCallResult]:
        """Run several calls in order. Sequential on purpose: the budget is shared."""
        results: list[ToolCallResult] = []
        for name, arguments in calls:
            results.append(await self.execute(name, arguments))
        return results
