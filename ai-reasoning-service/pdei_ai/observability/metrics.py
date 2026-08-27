"""Prometheus metrics - names are normative (platform contract section 13).

The four ``pdei_ai_*`` metrics in section 13 are declared first and their names
and label sets are fixed; Grafana dashboards and the Java side depend on them.
Everything after them is local detail for this service and stays under the same
``pdei_ai_`` prefix.

Collectors are created through ``_counter`` / ``_histogram`` helpers that
tolerate a re-import into an already-populated default registry, which happens
constantly under pytest.
"""

from __future__ import annotations

import contextlib
from typing import Any

from prometheus_client import REGISTRY, CollectorRegistry, Counter, Gauge, Histogram

_LATENCY_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0)
_TOOL_BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)


def _existing(name: str, registry: CollectorRegistry) -> Any | None:
    """Find an already-registered collector by any of its exported names."""
    mapping = getattr(registry, "_names_to_collectors", {})
    for candidate in (name, f"{name}_total", f"{name}_created"):
        if candidate in mapping:
            return mapping[candidate]
    return None


def _counter(name: str, documentation: str, labels: tuple[str, ...] = ()) -> Counter:
    existing = _existing(name, REGISTRY)
    if existing is not None:
        return existing  # type: ignore[return-value]
    return Counter(name, documentation, labels)


def _histogram(
    name: str, documentation: str, labels: tuple[str, ...] = (), buckets: tuple[float, ...] = ()
) -> Histogram:
    existing = _existing(name, REGISTRY)
    if existing is not None:
        return existing  # type: ignore[return-value]
    return Histogram(name, documentation, labels, buckets=buckets or Histogram.DEFAULT_BUCKETS)


def _gauge(name: str, documentation: str, labels: tuple[str, ...] = ()) -> Gauge:
    existing = _existing(name, REGISTRY)
    if existing is not None:
        return existing  # type: ignore[return-value]
    return Gauge(name, documentation, labels)


# ---------------------------------------------------------------------------
# Contract section 13 - exact names, do not rename
# ---------------------------------------------------------------------------

AI_REQUESTS_TOTAL = _counter(
    "pdei_ai_requests_total",
    "AI reasoning requests by provider and outcome.",
    ("provider", "outcome"),
)

AI_ADMISSION_TOTAL = _counter(
    "pdei_ai_admission_total",
    "Admission control decisions by decision (ADMITTED or the short-circuit reason).",
    ("decision",),
)

AI_LATENCY_SECONDS = _histogram(
    "pdei_ai_latency_seconds",
    "End-to-end AI reasoning latency by provider.",
    ("provider",),
    _LATENCY_BUCKETS,
)

AI_UNSUPPORTED_CLAIMS_TOTAL = _counter(
    "pdei_ai_unsupported_claims_total",
    "Claims dropped because they cited evidence absent from the investigation context.",
)

# ---------------------------------------------------------------------------
# Service-local detail (same prefix, not referenced by other modules)
# ---------------------------------------------------------------------------

AI_TOOL_CALLS_TOTAL = _counter(
    "pdei_ai_tool_calls_total",
    "Read-only tool invocations by tool name and outcome.",
    ("tool", "outcome"),
)

AI_TOOL_LATENCY_SECONDS = _histogram(
    "pdei_ai_tool_latency_seconds",
    "Latency of read-only tool calls into api-gateway-service.",
    ("tool",),
    _TOOL_BUCKETS,
)

AI_TOOL_REJECTED_TOTAL = _counter(
    "pdei_ai_tool_rejected_total",
    "Tool calls refused by the executor before any HTTP request was made.",
    ("reason",),
)

AI_TOKENS_TOTAL = _counter(
    "pdei_ai_tokens_total",
    "Model tokens accounted, by provider and kind (prompt or completion).",
    ("provider", "kind"),
)

AI_REPAIR_ATTEMPTS_TOTAL = _counter(
    "pdei_ai_repair_attempts_total",
    "Repair re-prompts issued after a provider returned unparseable or invalid JSON.",
    ("provider", "outcome"),
)

AI_BUDGET_DECISIONS_TOTAL = _counter(
    "pdei_ai_budget_decisions_total",
    "Redis budget and token-bucket decisions.",
    ("gate", "outcome"),
)

AI_PROVIDER_FALLBACKS_TOTAL = _counter(
    "pdei_ai_provider_fallbacks_total",
    "Times the registry moved down the fallback chain.",
    ("requested", "selected"),
)

AI_ACTIVE_PROVIDER = _gauge(
    "pdei_ai_active_provider",
    "1 for the currently selected reasoner, 0 for the others.",
    ("provider",),
)


# ---------------------------------------------------------------------------
# Small helpers so call sites never wrap metrics in try/except themselves.
# Metrics must never break a reasoning path.
# ---------------------------------------------------------------------------


def record_request(provider: str, outcome: str) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        AI_REQUESTS_TOTAL.labels(provider=provider, outcome=outcome).inc()


def record_latency(provider: str, seconds: float) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        AI_LATENCY_SECONDS.labels(provider=provider).observe(max(0.0, seconds))


def record_admission(decision: str) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        AI_ADMISSION_TOTAL.labels(decision=decision).inc()


def record_unsupported_claims(count: int = 1) -> None:
    if count <= 0:
        return
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        AI_UNSUPPORTED_CLAIMS_TOTAL.inc(count)


def record_tool_call(tool: str, outcome: str, seconds: float | None = None) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        AI_TOOL_CALLS_TOTAL.labels(tool=tool, outcome=outcome).inc()
        if seconds is not None:
            AI_TOOL_LATENCY_SECONDS.labels(tool=tool).observe(max(0.0, seconds))


def record_tool_rejected(reason: str) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        AI_TOOL_REJECTED_TOTAL.labels(reason=reason).inc()


def record_tokens(provider: str, prompt_tokens: int, completion_tokens: int) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        if prompt_tokens:
            AI_TOKENS_TOTAL.labels(provider=provider, kind="prompt").inc(prompt_tokens)
        if completion_tokens:
            AI_TOKENS_TOTAL.labels(provider=provider, kind="completion").inc(completion_tokens)


def record_repair(provider: str, outcome: str) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        AI_REPAIR_ATTEMPTS_TOTAL.labels(provider=provider, outcome=outcome).inc()


def record_budget(gate: str, outcome: str) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        AI_BUDGET_DECISIONS_TOTAL.labels(gate=gate, outcome=outcome).inc()


def record_provider_fallback(requested: str, selected: str) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        AI_PROVIDER_FALLBACKS_TOTAL.labels(requested=requested, selected=selected).inc()


def set_active_provider(provider: str, known: tuple[str, ...] = ("gemini", "mock", "null")) -> None:
    with contextlib.suppress(Exception):  # metrics are best effort, never fatal
        for name in known:
            AI_ACTIVE_PROVIDER.labels(provider=name).set(1.0 if name == provider else 0.0)
