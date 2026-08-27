"""Pydantic v2 mirrors of the cross-language PDEI types.

Field names are camelCase on purpose. These models must stay byte-identical to
the Java records in ``evidence-core`` (``core.model.*``) and to the TypeScript
types in ``frontend/src/lib/types``. Cross-language parity
(SHARED-LIBRARY-API section 4) outranks Python naming convention here, which is
why ``pep8-naming`` is not enabled in the ruff configuration.
"""

from pdei_ai.models.admission import (
    AdmissionDecision,
    AdmissionRequest,
    ShortCircuit,
    admission_request_from_payload,
)
from pdei_ai.models.common import (
    EVIDENCE_ID_PATTERN,
    Confidence,
    EvidenceId,
    Instant,
    InvestigationId,
    Money,
    PdeiModel,
    is_evidence_id,
    utc_now,
)
from pdei_ai.models.enums import (
    AggregateType,
    CaseStatus,
    ChaosType,
    DisputeReasonCode,
    DisputeStatus,
    EventSource,
    EventType,
    EvidenceSource,
    EvidenceStatus,
    EvidenceType,
    GapSeverity,
    GapType,
    InvestigationClassification,
    ReadinessBand,
    RecommendedAction,
    RequirementStrength,
    SafetyDecision,
)
from pdei_ai.models.events import CanonicalEvent
from pdei_ai.models.investigation import (
    Citation,
    ContradictionRef,
    EvidenceRef,
    GapRef,
    HistoricalContext,
    InvestigationContext,
    InvestigationResult,
    ModelMetadata,
    PolicyConstraints,
    RequirementRef,
    TimelineEntry,
)
from pdei_ai.models.narrative import NarrativeRequest, NarrativeResult

__all__ = [
    "EVIDENCE_ID_PATTERN",
    "AdmissionDecision",
    "AdmissionRequest",
    "AggregateType",
    "CanonicalEvent",
    "CaseStatus",
    "ChaosType",
    "Citation",
    "Confidence",
    "ContradictionRef",
    "DisputeReasonCode",
    "DisputeStatus",
    "EventSource",
    "EventType",
    "EvidenceId",
    "EvidenceRef",
    "EvidenceSource",
    "EvidenceStatus",
    "EvidenceType",
    "GapRef",
    "GapSeverity",
    "GapType",
    "HistoricalContext",
    "Instant",
    "InvestigationClassification",
    "InvestigationContext",
    "InvestigationId",
    "InvestigationResult",
    "ModelMetadata",
    "Money",
    "NarrativeRequest",
    "NarrativeResult",
    "PdeiModel",
    "PolicyConstraints",
    "ReadinessBand",
    "RecommendedAction",
    "RequirementRef",
    "RequirementStrength",
    "SafetyDecision",
    "ShortCircuit",
    "TimelineEntry",
    "admission_request_from_payload",
    "is_evidence_id",
    "utc_now",
]
