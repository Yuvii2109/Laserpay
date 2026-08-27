"""The ``EvidenceReasoner`` protocol - the AI provider abstraction (contract 9.5).

Everything above this line (routes, services, the self-check) is provider
agnostic. Everything below it (Gemini SDK calls, prompts, token accounting) is
implementation detail. Swapping ``GeminiReasoner`` for ``MockReasoner`` changes
nothing about how the platform behaves, which is what makes the whole stack
runnable with no API key and testable with no network.

Three methods, no more:

* ``investigate`` - context in, structured proposal out;
* ``narrate`` - context in, evidence-backed prose out;
* ``health`` - is this provider usable right now?

Implementations must not mutate the context they are given, must not raise for
ordinary model failure (they degrade or raise ``ReasonerError``, which the
service handles), and must never claim authority over financial state.
"""

from __future__ import annotations

from datetime import datetime
from typing import Protocol, runtime_checkable

from pydantic import Field

from pdei_ai.models.common import Instant, PdeiModel, utc_now
from pdei_ai.models.investigation import InvestigationContext, InvestigationResult
from pdei_ai.models.narrative import NarrativeRequest, NarrativeResult


class ReasonerError(RuntimeError):
    """A provider failed in a way the caller may retry or fall back from."""


class ReasonerUnavailable(ReasonerError):
    """The provider is not usable at all: no API key, SDK missing, circuit open."""


class InvalidModelOutput(ReasonerError):
    """The provider answered, but the answer was not valid against the schema.

    Carries the raw text so the repair pass can show the model its own output.
    """

    def __init__(self, message: str, raw_output: str = "") -> None:
        super().__init__(message)
        self.raw_output = raw_output


class ReasonerHealth(PdeiModel):
    """Health of one provider, as reported by ``GET /v1/providers`` and ``/ready``."""

    provider: str
    model: str
    healthy: bool
    detail: str = ""
    checkedAt: Instant = Field(default_factory=utc_now)


@runtime_checkable
class EvidenceReasoner(Protocol):
    """The only interface the rest of the service knows about."""

    #: Stable provider key: ``gemini``, ``mock`` or ``null``. Appears in metrics
    #: labels, in ``ModelMetadata.provider`` and in the audit trail.
    name: str

    #: Model identifier for ``ModelMetadata.model``.
    model: str

    async def investigate(self, context: InvestigationContext) -> InvestigationResult:
        """Analyse a curated context and propose a classification.

        Must return a schema-valid ``InvestigationResult``. Must not invent
        evidence ids; unsupported claims are filtered downstream, and a provider
        that routinely produces them is failing at its job.
        """
        ...

    async def narrate(self, request: NarrativeRequest) -> NarrativeResult:
        """Draft an evidence-backed representment narrative."""
        ...

    async def health(self) -> ReasonerHealth:
        """Report whether this provider can currently answer. Must not raise."""
        ...


class BaseReasoner:
    """Shared plumbing for the concrete reasoners.

    Not required by the protocol - a reasoner only has to satisfy the three
    methods - but it keeps the citable-universe logic in one place so every
    provider computes it the same way.
    """

    name: str = "base"
    model: str = "unknown"

    @staticmethod
    def citable_ids(context: InvestigationContext) -> set[str]:
        """Evidence ids the model is permitted to reference for this context."""
        return context.evidence_ids()

    @staticmethod
    def now() -> datetime:
        return utc_now()
