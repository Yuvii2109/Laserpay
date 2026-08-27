"""Narrative types - ``POST /v1/narrative`` (platform contract 8.6).

A representment narrative is the prose a human reviewer reads and a scheme
representative eventually reads. It is generated text, so it is the single
highest-risk artefact this service produces: prose is where a model invents a
tracking number.

Two rules therefore apply to everything in this module:

1. Every factual sentence must be backed by a ``Citation`` whose evidence id is
   present in the supplied ``InvestigationContext``.
2. Any ``EV-...`` token appearing in the prose that is not in the context is
   redacted by ``NarrativeService`` before the text leaves this process.
"""

from __future__ import annotations

from pydantic import Field, field_validator

from pdei_ai.models.common import Instant, PdeiModel, utc_now
from pdei_ai.models.enums import InvestigationClassification
from pdei_ai.models.investigation import Citation, InvestigationContext, ModelMetadata


class NarrativeRequest(PdeiModel):
    """Request body of ``POST /v1/narrative``.

    The Java ``HttpAiReasoningClient`` posts a bare ``InvestigationContext``, so
    every field except the context itself is optional and the route accepts both
    shapes (see ``pdei_ai.api.routes.narrative``).
    """

    context: InvestigationContext
    classification: InvestigationClassification | None = Field(
        default=None,
        description="Classification already established, when the caller has one.",
    )
    tone: str = Field(default="FORMAL", description="FORMAL | PLAIN | CONCISE.")
    maxWords: int = Field(default=350, ge=50, le=2000)

    @field_validator("tone")
    @classmethod
    def _known_tone(cls, value: str) -> str:
        tone = value.strip().upper()
        if tone not in {"FORMAL", "PLAIN", "CONCISE"}:
            raise ValueError("tone must be FORMAL, PLAIN or CONCISE")
        return tone


class NarrativeResult(PdeiModel):
    """Response body of ``POST /v1/narrative``.

    ``narrative`` is the only field the Java client reads
    (``response.get("narrative")``); the rest is for the Case X-Ray UI and the
    audit trail.
    """

    investigationId: str
    narrative: str
    citations: list[Citation] = Field(default_factory=list)
    evidenceIds: list[str] = Field(
        default_factory=list,
        description="Evidence ids actually referenced, after the unsupported-claim filter.",
    )
    droppedClaims: int = Field(
        default=0,
        ge=0,
        description="Claims removed because they cited evidence absent from the context.",
    )
    redactedReferences: int = Field(
        default=0,
        ge=0,
        description="EV- tokens removed from the prose because the context did not contain them.",
    )
    generatedAt: Instant = Field(default_factory=utc_now)
    modelMetadata: ModelMetadata

    @property
    def word_count(self) -> int:
        return len(self.narrative.split())
