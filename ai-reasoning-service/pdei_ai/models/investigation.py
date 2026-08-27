"""``InvestigationContext`` and ``InvestigationResult`` - platform contract 9.1 / 9.2.

These two types are the entire AI contract. Everything the model is allowed to
see arrives in an ``InvestigationContext``; everything it is allowed to say
leaves in an ``InvestigationResult``. Both are field-identical to the Java
records in ``com.laserpay.pdei.core.model`` and to ``frontend/src/lib/types/ai.ts``.

Design notes worth keeping:

* ``InvestigationContext`` is *curated*. The model has no database access; the
  only way it can widen its view is the read-only ``/api/v1/ai-tools/*`` tool
  layer, and every id it learns there still has to be reconciled against this
  context before it may appear in a result.
* ``InvestigationResult`` is a *proposal*. Nothing in it is trusted. The Java
  ``AiResultValidator`` (contract 9.3) re-checks every field against Postgres
  before any state changes, and this service pre-filters unsupported claims so
  the two layers agree about what a claim even is.
"""

from __future__ import annotations

from typing import Any

from pydantic import Field, field_validator, model_validator

from pdei_ai.models.common import (
    Confidence,
    EvidenceId,
    Instant,
    InvestigationId,
    Money,
    PdeiModel,
    is_evidence_id,
)
from pdei_ai.models.enums import (
    AggregateType,
    DisputeReasonCode,
    EvidenceSource,
    EvidenceStatus,
    EvidenceType,
    GapSeverity,
    GapType,
    InvestigationClassification,
    RecommendedAction,
    RequirementStrength,
)

# ---------------------------------------------------------------------------
# Context parts
# ---------------------------------------------------------------------------


class EvidenceRef(PdeiModel):
    """One evidence item as the model sees it (contract 9.1 ``evidence[]``).

    The contract's illustrative JSON carries ``evidenceId, type, status, sha256,
    createdAt, summary, version``; the Java ``EvidenceView`` carries more. The
    extra fields are declared optional here so provenance and quality signals
    survive the trip instead of being dropped by ``extra="ignore"``.
    """

    evidenceId: EvidenceId
    type: EvidenceType
    status: EvidenceStatus
    sha256: str | None = Field(default=None, description="Content hash; provenance anchor.")
    createdAt: Instant | None = None
    summary: str | None = Field(default=None, description="Short human description.")
    version: int = Field(default=1, ge=1)

    # --- richer view supplied by evidence-core; all optional -----------------
    merchantId: str | None = None
    transactionId: str | None = None
    source: EvidenceSource | None = None
    objectKey: str | None = None
    filename: str | None = None
    contentType: str | None = None
    sizeBytes: int | None = None
    sourceEventId: str | None = None
    parentEvidenceId: str | None = None
    relatedEntityId: str | None = None
    qualityScore: float | None = Field(default=None, ge=0.0, le=1.0)
    provenanceVerified: bool | None = None
    observedAt: Instant | None = None
    expiresAt: Instant | None = None

    @property
    def is_usable(self) -> bool:
        """ACTIVE or EXPIRING. Anything else must not be cited as current proof."""
        return self.status.is_usable


class RequirementRef(PdeiModel):
    """One evidence requirement for this reason code (contract 9.1 ``requirements[]``)."""

    type: EvidenceType
    strength: RequirementStrength
    satisfied: bool = False
    satisfyingEvidenceIds: list[EvidenceId] = Field(default_factory=list)
    weight: int | None = None
    note: str | None = None

    @property
    def is_mandatory(self) -> bool:
        return self.strength is RequirementStrength.MANDATORY


class GapRef(PdeiModel):
    """One readiness gap (contract 9.1 ``gaps[]``)."""

    type: GapType
    evidenceType: EvidenceType | None = None
    severity: GapSeverity = GapSeverity.MEDIUM
    gapId: str | None = None
    transactionId: str | None = None
    evidenceId: str | None = Field(
        default=None,
        description="Id of the entity this gap points at. An evidence id (EV-) when the gap "
        "is about a document; for CONTRADICTORY gaps the Java GapDetector reuses "
        "ContradictionView.left(), which is a domain entity id (DLV-, SHP-, ORD-, TX-, ...) "
        "whenever no evidence documents the entity. Not pattern-constrained for that reason.",
    )
    detail: str | None = None
    detectedAt: Instant | None = None
    expiresAt: Instant | None = None

    @property
    def is_blocking(self) -> bool:
        return self.severity.is_blocking


class ContradictionRef(PdeiModel):
    """A conflict between two evidence items (contract 9.1 ``contradictions[]``).

    ``left`` / ``right`` carry an evidence id (``EV-``) when the conflict is
    document-backed, and a domain entity id otherwise: ``ContradictionDetector.ref``
    on the Java side falls back to the raw entity id (``DLV-``, ``SHP-``, ``ORD-``,
    ``REF-``, ``PAY-``) when no evidence documents the entity, and several rules
    pass the transaction id (``TX-``) directly. They are therefore plain strings,
    not ``EvidenceId``. They stay optional because ``ContradictionView.narrative(...)``
    on the Java side produces a description with no specific pair attached.
    """

    left: str | None = Field(
        default=None,
        description="Left side of the conflict: an evidence id when document-backed, "
        "otherwise a domain entity id.",
    )
    right: str | None = Field(
        default=None,
        description="Right side of the conflict: an evidence id when document-backed, "
        "otherwise a domain entity id.",
    )
    field: str | None = Field(default=None, description="Conflicting field, e.g. deliveredAt.")
    detail: str | None = None
    severity: GapSeverity = GapSeverity.MEDIUM
    leftValue: str | None = None
    rightValue: str | None = None
    detectedAt: Instant | None = None

    @model_validator(mode="before")
    @classmethod
    def _accept_bare_string(cls, value: Any) -> Any:
        """Tolerate a plain string, matching the Java ``ContradictionView`` deserializer."""
        if isinstance(value, str):
            return {"detail": value, "severity": GapSeverity.MEDIUM.value}
        return value

    def referenced_evidence_ids(self) -> list[str]:
        return [side for side in (self.left, self.right) if side]


class PolicyConstraints(PdeiModel):
    """Deterministic limits the model is told about (contract 9.1 ``policyConstraints``).

    Telling the model the thresholds does not give it authority over them: the
    Java policy gate re-applies every one of these after the fact. It simply
    stops the model from proposing something that is guaranteed to be refused.
    """

    autoPrepareMinConfidence: float = Field(default=0.90, ge=0.0, le=1.0)
    maxContradictions: int = Field(default=0, ge=0)
    prohibitedEvidenceTypes: list[EvidenceType] = Field(default_factory=list)


class TimelineEntry(PdeiModel):
    """One entry of the unified event/evidence timeline (contract 9.1 ``timeline[]``)."""

    at: Instant | None = None
    eventType: str | None = None
    summary: str | None = None
    entryId: str | None = None
    aggregateType: AggregateType | None = None
    aggregateId: str | None = None
    source: str | None = None
    details: dict[str, Any] = Field(default_factory=dict)


class HistoricalContext(PdeiModel):
    """Merchant-level priors (contract 9.1 ``historicalContext``)."""

    merchantWinRate: float = Field(default=0.0, ge=0.0, le=1.0)
    similarCases: int = Field(default=0, ge=0)


# ---------------------------------------------------------------------------
# Result parts
# ---------------------------------------------------------------------------


class Citation(PdeiModel):
    """A factual claim bound to the evidence that supports it (contract 9.2).

    A citation whose ``evidenceId`` is not present in the supplied context is
    dropped by ``InvestigationService`` before the result leaves this process,
    and counted in ``pdei_ai_unsupported_claims_total``.
    """

    claim: str = Field(min_length=1, description="One factual statement. No claim without proof.")
    evidenceId: EvidenceId

    @field_validator("claim")
    @classmethod
    def _trim_claim(cls, value: str) -> str:
        claim = value.strip()
        if not claim:
            raise ValueError("claim must not be blank")
        return claim


class ModelMetadata(PdeiModel):
    """Provenance of the answer itself (contract 9.2 ``modelMetadata``).

    Always filled in by this service, never by the model: a provider that could
    describe itself could also lie about being deterministic.
    """

    provider: str = Field(description="gemini | mock | null | deterministic")
    model: str
    promptTokens: int = Field(default=0, ge=0)
    completionTokens: int = Field(default=0, ge=0)
    latencyMs: int = Field(default=0, ge=0)
    attempt: int = Field(default=1, ge=1)

    @property
    def total_tokens(self) -> int:
        return self.promptTokens + self.completionTokens


# ---------------------------------------------------------------------------
# The two top-level types
# ---------------------------------------------------------------------------


class InvestigationContext(PdeiModel):
    """Everything the model is allowed to see (platform contract 9.1)."""

    investigationId: InvestigationId
    caseId: str | None = None
    disputeId: str | None = None
    merchantId: str | None = None
    transactionId: str | None = None
    reasonCode: DisputeReasonCode | None = None
    disputeAmount: Money | None = None
    deadlineAt: Instant | None = None
    transactionSummary: dict[str, Any] = Field(default_factory=dict)
    evidence: list[EvidenceRef] = Field(default_factory=list)
    requirements: list[RequirementRef] = Field(default_factory=list)
    gaps: list[GapRef] = Field(default_factory=list)
    contradictions: list[ContradictionRef] = Field(default_factory=list)
    policyConstraints: PolicyConstraints = Field(default_factory=PolicyConstraints)
    timeline: list[TimelineEntry] = Field(default_factory=list)
    historicalContext: HistoricalContext = Field(default_factory=HistoricalContext)

    # --- derived helpers used by the reasoners and the self-check ------------

    def evidence_ids(self) -> set[str]:
        """Every evidence id present in this context - the citable universe."""
        ids: set[str] = {item.evidenceId for item in self.evidence}
        for requirement in self.requirements:
            ids.update(requirement.satisfyingEvidenceIds)
        for gap in self.gaps:
            if gap.evidenceId:
                ids.add(gap.evidenceId)
        for contradiction in self.contradictions:
            ids.update(contradiction.referenced_evidence_ids())
        return {value for value in ids if is_evidence_id(value)}

    def usable_evidence(self) -> list[EvidenceRef]:
        return [item for item in self.evidence if item.is_usable]

    def unsatisfied_mandatory(self) -> list[RequirementRef]:
        return [r for r in self.requirements if r.is_mandatory and not r.satisfied]

    def satisfied_requirements(self) -> list[RequirementRef]:
        return [r for r in self.requirements if r.satisfied]

    def hours_until_deadline(self, now: Any) -> float | None:
        """Hours left before the representment deadline, or ``None`` when unknown."""
        if self.deadlineAt is None or now is None:
            return None
        return (self.deadlineAt - now).total_seconds() / 3600.0

    def past_deadline(self, now: Any) -> bool:
        return self.deadlineAt is not None and now is not None and now > self.deadlineAt


class InvestigationResult(PdeiModel):
    """The model's schema-constrained proposal (platform contract 9.2).

    ``schemas/ai/investigation-result.schema.json`` is generated from this class
    and is the referee whenever Java, Python and TypeScript disagree.
    """

    investigationId: InvestigationId
    classification: InvestigationClassification
    confidence: Confidence = 0.0
    supportingEvidence: list[EvidenceId] = Field(default_factory=list)
    missingEvidence: list[EvidenceType] = Field(
        default_factory=list,
        description="Evidence TYPES that would strengthen the case - types, not ids, "
        "because missing evidence has no id yet.",
    )
    contradictions: list[ContradictionRef] = Field(default_factory=list)
    reasoningSummary: str = Field(default="", description="Why this classification, in prose.")
    narrative: str = Field(default="", description="Evidence-backed representment narrative.")
    recommendedAction: RecommendedAction
    citations: list[Citation] = Field(default_factory=list)
    modelMetadata: ModelMetadata

    @field_validator("supportingEvidence")
    @classmethod
    def _dedupe_supporting(cls, value: list[str]) -> list[str]:
        """Order-preserving dedupe; a repeated id is not extra proof."""
        return list(dict.fromkeys(value))

    @field_validator("missingEvidence")
    @classmethod
    def _dedupe_missing(cls, value: list[EvidenceType]) -> list[EvidenceType]:
        return list(dict.fromkeys(value))

    def referenced_evidence_ids(self) -> list[str]:
        """Every evidence id this result leans on, from both lists (Java parity)."""
        seen: dict[str, None] = {}
        for value in self.supportingEvidence:
            seen.setdefault(value, None)
        for citation in self.citations:
            seen.setdefault(citation.evidenceId, None)
        return list(seen)

    def with_model_metadata(self, metadata: ModelMetadata) -> InvestigationResult:
        return self.model_copy(update={"modelMetadata": metadata})
