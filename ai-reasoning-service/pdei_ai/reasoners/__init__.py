"""The AI provider abstraction (platform contract 9.5).

``EvidenceReasoner`` is the boundary. ``GeminiReasoner`` is the only file in the
platform that imports an AI SDK; ``MockReasoner`` keeps the whole stack runnable
and testable with no API key; ``NullReasoner`` turns reasoning off without
turning the platform off.
"""

from pdei_ai.reasoners.base import (
    BaseReasoner,
    EvidenceReasoner,
    InvalidModelOutput,
    ReasonerError,
    ReasonerHealth,
    ReasonerUnavailable,
)
from pdei_ai.reasoners.mock import MockReasoner, seed_for
from pdei_ai.reasoners.null import NullReasoner
from pdei_ai.reasoners.registry import (
    KNOWN_PROVIDERS,
    TERMINAL_FALLBACK,
    ReasonerRegistry,
    build_registry,
)

__all__ = [
    "KNOWN_PROVIDERS",
    "TERMINAL_FALLBACK",
    "BaseReasoner",
    "EvidenceReasoner",
    "InvalidModelOutput",
    "MockReasoner",
    "NullReasoner",
    "ReasonerError",
    "ReasonerHealth",
    "ReasonerRegistry",
    "ReasonerUnavailable",
    "build_registry",
    "seed_for",
]
