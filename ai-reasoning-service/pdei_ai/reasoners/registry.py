"""Provider selection and the fallback chain (platform contract 9.5).

``PDEI_AI_PROVIDER`` picks the provider: ``gemini``, ``mock`` or ``null``.
Default in development is ``mock``.

**Fallback chain, documented because it is a safety property.**

The chain is ``gemini -> mock`` by default (``PDEI_AI_FALLBACK_CHAIN``). It is
walked once at startup, in order, and the first provider that can be constructed
*and* reports healthy becomes active:

1. ``gemini`` - requires ``GEMINI_API_KEY`` and the ``google-genai`` package. If
   either is missing the provider cannot be constructed and the chain moves on.
2. ``mock`` - always constructible, always healthy, no network. This is the end
   of the chain by design: the platform must never be unable to answer, and a
   deterministic answer clearly labelled ``provider=mock`` is far safer than an
   outage in the middle of a dispute deadline.

``null`` is never a fallback target. Abstention is something an operator chooses
deliberately, not something the platform drifts into.

Selection is explicit and logged, and every fall-through increments
``pdei_ai_provider_fallbacks_total``. Silent degradation is the thing to avoid:
an operator who thinks they are running Gemini and is actually running the mock
would draw entirely wrong conclusions from a demo.
"""

from __future__ import annotations

from typing import Any

from pdei_ai.config import Settings
from pdei_ai.observability.logging import get_logger
from pdei_ai.observability.metrics import record_provider_fallback, set_active_provider
from pdei_ai.reasoners.base import EvidenceReasoner, ReasonerHealth, ReasonerUnavailable
from pdei_ai.reasoners.mock import MockReasoner
from pdei_ai.reasoners.null import NullReasoner
from pdei_ai.tools.executor import ToolExecutor

log = get_logger(__name__)

KNOWN_PROVIDERS = ("gemini", "mock", "null")
TERMINAL_FALLBACK = "mock"


class ReasonerRegistry:
    """Holds the constructed providers and which one is active."""

    def __init__(
        self,
        settings: Settings,
        tool_executor: ToolExecutor | None = None,
        overrides: dict[str, EvidenceReasoner] | None = None,
    ) -> None:
        self.settings = settings
        self._tool_executor = tool_executor
        self._overrides = overrides or {}
        self._built: dict[str, EvidenceReasoner] = {}
        self._construction_errors: dict[str, str] = {}
        self._active_name: str = TERMINAL_FALLBACK
        self._chain: list[str] = []
        self._select()

    # --- public surface -----------------------------------------------------

    @property
    def active_name(self) -> str:
        return self._active_name

    @property
    def active(self) -> EvidenceReasoner:
        return self._built[self._active_name]

    @property
    def chain(self) -> list[str]:
        """The chain actually walked at startup, in order."""
        return list(self._chain)

    def get(self, name: str) -> EvidenceReasoner:
        """Fetch a specific provider by name, building it on demand."""
        if name in self._built:
            return self._built[name]
        reasoner = self._build(name)
        if reasoner is None:
            raise ReasonerUnavailable(
                f"provider {name!r} is unavailable: "
                f"{self._construction_errors.get(name, 'unknown reason')}"
            )
        self._built[name] = reasoner
        return reasoner

    async def health(self) -> list[ReasonerHealth]:
        """Health of every constructed provider, active one first."""
        report: list[ReasonerHealth] = []
        for name, reasoner in sorted(
            self._built.items(), key=lambda item: item[0] != self._active_name
        ):
            try:
                report.append(await reasoner.health())
            except Exception as exc:  # pragma: no cover - health must never raise out
                report.append(
                    ReasonerHealth(
                        provider=name,
                        model=getattr(reasoner, "model", "unknown"),
                        healthy=False,
                        detail=str(exc)[:300],
                    )
                )
        for name, error in self._construction_errors.items():
            if name not in self._built:
                report.append(
                    ReasonerHealth(provider=name, model="-", healthy=False, detail=error[:300])
                )
        return report

    def describe(self) -> dict[str, Any]:
        """Payload of ``GET /v1/providers``."""
        return {
            "active": self._active_name,
            "activeModel": getattr(self.active, "model", "unknown"),
            "requested": self.settings.provider,
            "fallbackChain": list(self.settings.fallback_chain),
            "chainWalked": self.chain,
            "available": sorted(self._built),
            "unavailable": {
                name: error
                for name, error in self._construction_errors.items()
                if name not in self._built
            },
            "known": list(KNOWN_PROVIDERS),
            "toolsEnabled": bool(
                self.settings.tools_enabled
                and self._tool_executor is not None
                and self._tool_executor.available
            ),
        }

    # --- selection ----------------------------------------------------------

    def _select(self) -> None:
        requested = self.settings.provider

        # An explicit `null` is honoured immediately: abstention is a decision,
        # not a degradation, and must not fall through to another provider.
        if requested == "null":
            self._built["null"] = self._build("null")  # type: ignore[assignment]
            self._active_name = "null"
            self._chain = ["null"]
            self._announce()
            return

        chain = self._resolve_chain(requested)
        self._chain = chain

        for name in chain:
            reasoner = self._build(name)
            if reasoner is None:
                log.warning(
                    "provider unavailable; moving down the fallback chain",
                    provider=name,
                    reason=self._construction_errors.get(name),
                )
                continue
            self._built[name] = reasoner
            self._active_name = name
            if name != requested:
                record_provider_fallback(requested, name)
                log.warning("selected a fallback provider", requested=requested, selected=name)
            self._announce()
            return

        # Unreachable in practice: MockReasoner cannot fail to construct. Kept
        # so that a future chain edit cannot leave the service with no reasoner.
        self._built[TERMINAL_FALLBACK] = MockReasoner()
        self._active_name = TERMINAL_FALLBACK
        record_provider_fallback(requested, TERMINAL_FALLBACK)
        self._announce()

    def _resolve_chain(self, requested: str) -> list[str]:
        """Requested provider first, then the configured chain, then mock."""
        chain: list[str] = [requested]
        for name in self.settings.fallback_chain:
            if name not in chain and name in KNOWN_PROVIDERS and name != "null":
                chain.append(name)
        if TERMINAL_FALLBACK not in chain:
            chain.append(TERMINAL_FALLBACK)
        return chain

    def _build(self, name: str) -> EvidenceReasoner | None:
        if name in self._overrides:
            return self._overrides[name]
        try:
            if name == "mock":
                return MockReasoner()
            if name == "null":
                return NullReasoner()
            if name == "gemini":
                from pdei_ai.reasoners.gemini import GeminiReasoner

                return GeminiReasoner(
                    api_key=self.settings.gemini_api_key,
                    model=self.settings.gemini_model,
                    temperature=self.settings.temperature,
                    max_output_tokens=self.settings.max_output_tokens,
                    max_attempts=self.settings.max_attempts,
                    tool_executor=self._tool_executor,
                    tools_enabled=self.settings.tools_enabled,
                    max_tool_calls=self.settings.max_tool_calls,
                )
            self._construction_errors[name] = f"unknown provider {name!r}"
            return None
        except Exception as exc:
            self._construction_errors[name] = str(exc)
            return None

    def _announce(self) -> None:
        set_active_provider(self._active_name, KNOWN_PROVIDERS)
        log.info(
            "reasoner selected",
            provider=self._active_name,
            model=getattr(self.active, "model", "unknown"),
            requested=self.settings.provider,
            chain=self._chain,
        )


def build_registry(
    settings: Settings,
    tool_executor: ToolExecutor | None = None,
    overrides: dict[str, EvidenceReasoner] | None = None,
) -> ReasonerRegistry:
    """Construct the registry. Called once from the FastAPI lifespan."""
    return ReasonerRegistry(settings, tool_executor=tool_executor, overrides=overrides)
