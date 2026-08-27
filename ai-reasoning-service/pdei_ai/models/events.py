"""``CanonicalEvent`` - the Python mirror of the platform event envelope.

Field-identical to ``com.laserpay.pdei.common.event.CanonicalEvent`` and to
``frontend/src/lib/types/events.ts`` (platform contract section 3).

The AI service does not consume Kafka. This type exists so that timeline
entries, tool responses and any future event tail arriving over HTTP can be
parsed with the same envelope everyone else uses, and so the JSON Schemas stay
in sync across the three languages.
"""

from __future__ import annotations

from typing import Any

from pydantic import Field, field_validator

from pdei_ai.models.common import Instant, PdeiModel
from pdei_ai.models.enums import AggregateType, EventSource, EventType


class CanonicalEvent(PdeiModel):
    """One event on a ``pdei.*.v1`` canonical topic."""

    eventId: str = Field(description="UUID string; the platform-wide dedupe key.")
    eventType: EventType
    schemaVersion: int = Field(default=1, ge=1)
    aggregateType: AggregateType
    aggregateId: str
    merchantId: str
    correlationId: str | None = None
    causationId: str | None = None
    occurredAt: Instant = Field(description="When the fact happened in the source system.")
    observedAt: Instant = Field(description="When PDEI first saw it. May lag occurredAt.")
    source: EventSource
    idempotencyKey: str | None = None
    payload: dict[str, Any] = Field(default_factory=dict)

    @field_validator("eventId", "aggregateId", "merchantId")
    @classmethod
    def _non_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("identifier must not be blank")
        return value

    @property
    def partition_key(self) -> str:
        """Mandatory Kafka partition key: ``merchantId + ":" + aggregateId``."""
        return f"{self.merchantId}:{self.aggregateId}"

    @property
    def is_late(self) -> bool:
        """True when the platform saw the event well after it occurred.

        Useful context for the model: late arrival explains an apparently
        out-of-order timeline without implying anything was tampered with.
        """
        return (self.observedAt - self.occurredAt).total_seconds() > 60.0
