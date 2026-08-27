"""``MockReasoner`` - deterministic, seeded, no network, no API key.

This is the default provider in development (contract 9.5) and it carries real
weight: it is what makes the whole platform runnable at zero cost, what makes
the demo reproducible, and what makes every test above the provider boundary
deterministic without mocking HTTP.

**Determinism guarantee.** The complete output - classification, confidence,
narrative wording, even ``modelMetadata.latencyMs`` and the token counts - is a
pure function of the ``InvestigationContext``. The seed is derived from
``investigationId`` via SHA-256, so the same investigation always produces a
byte-identical result, across processes and across machines. Nothing here reads
the clock or the system RNG, and ``latencyMs`` is a *simulated* figure precisely
so that the object stays stable; a wall-clock measurement would make the mock
irreproducible for the sake of a number nobody uses.

**Behavioural realism.** The decision tree mirrors the Java
``DeterministicInvestigator`` so that mock runs and deterministic fallbacks tell
the same story. The seeded randomness only moves confidence within the band the
decision tree already chose - it never changes a classification. A mock that
occasionally called an indefensible case defendable would be worse than useless
in a demo about financial safety.

**Safety.** Every citation is drawn from the supplied context. The mock cannot
produce an unsupported claim, which is exactly why the unsupported-claim filter
is tested against a hand-built bad result rather than against this class.
"""

from __future__ import annotations

import hashlib
import random
from typing import Any

from pdei_ai.models.enums import EvidenceType, InvestigationClassification, RecommendedAction
from pdei_ai.models.investigation import (
    Citation,
    InvestigationContext,
    InvestigationResult,
    ModelMetadata,
)
from pdei_ai.models.narrative import NarrativeRequest, NarrativeResult
from pdei_ai.prompts.system import SYSTEM_PROMPT_VERSION
from pdei_ai.reasoners.base import BaseReasoner, ReasonerHealth

PROVIDER_NAME = "mock"
MOCK_MODEL = f"pdei-mock-{SYSTEM_PROMPT_VERSION}"

# Confidence bands. Deliberately below any plausible autoPrepareMinConfidence
# except in the fully-satisfied case, so a mock run never auto-prepares a case
# that a real analyst would question.
_BAND_DEFENDABLE = (0.90, 0.985)
_BAND_WEAK = (0.55, 0.72)
_BAND_AMBIGUOUS = (0.35, 0.60)
_BAND_INSUFFICIENT = (0.30, 0.55)
_BAND_EMPTY = (0.10, 0.25)


def seed_for(investigation_id: str) -> int:
    """Stable 64-bit seed derived from the investigation id.

    SHA-256 rather than ``hash()``: Python's string hash is salted per process,
    so ``hash()`` would break determinism across restarts - the exact failure
    mode this class exists to avoid.
    """
    digest = hashlib.sha256(investigation_id.encode("utf-8")).hexdigest()
    return int(digest[:16], 16)


class MockReasoner(BaseReasoner):
    """A reasoner that behaves plausibly and repeats itself exactly."""

    name = PROVIDER_NAME
    model = MOCK_MODEL

    def __init__(self, model: str = MOCK_MODEL) -> None:
        self.model = model

    # --- EvidenceReasoner ---------------------------------------------------

    async def investigate(self, context: InvestigationContext) -> InvestigationResult:
        rng = random.Random(seed_for(context.investigationId))
        verdict = self._decide(context, rng)
        supporting = self._supporting_evidence(context)
        citations = self._citations(context, supporting)
        missing = self._missing_evidence(context)

        prompt_tokens, completion_tokens = self._token_estimate(context, verdict["summary"])

        return InvestigationResult(
            investigationId=context.investigationId,
            classification=verdict["classification"],
            confidence=verdict["confidence"],
            supportingEvidence=supporting,
            missingEvidence=missing,
            contradictions=list(context.contradictions),
            reasoningSummary=verdict["summary"],
            narrative=self._narrative_text(context, verdict["classification"], supporting),
            recommendedAction=verdict["action"],
            citations=citations,
            modelMetadata=ModelMetadata(
                provider=self.name,
                model=self.model,
                promptTokens=prompt_tokens,
                completionTokens=completion_tokens,
                # Simulated, not measured: see the module docstring.
                latencyMs=rng.randint(40, 260),
                attempt=1,
            ),
        )

    async def narrate(self, request: NarrativeRequest) -> NarrativeResult:
        context = request.context
        rng = random.Random(seed_for(context.investigationId) ^ 0x4E41_5252)  # "NARR"
        verdict = self._decide(context, random.Random(seed_for(context.investigationId)))
        classification = request.classification or verdict["classification"]
        supporting = self._supporting_evidence(context)
        citations = self._citations(context, supporting)
        text = self._narrative_text(context, classification, supporting, tone=request.tone)
        prompt_tokens, completion_tokens = self._token_estimate(context, text)

        return NarrativeResult(
            investigationId=context.investigationId,
            narrative=text,
            citations=citations,
            evidenceIds=supporting,
            modelMetadata=ModelMetadata(
                provider=self.name,
                model=self.model,
                promptTokens=prompt_tokens,
                completionTokens=completion_tokens,
                latencyMs=rng.randint(30, 180),
                attempt=1,
            ),
        )

    async def health(self) -> ReasonerHealth:
        return ReasonerHealth(
            provider=self.name,
            model=self.model,
            healthy=True,
            detail="deterministic in-process reasoner; always available, no API key required",
        )

    # --- decision tree ------------------------------------------------------

    def _decide(self, context: InvestigationContext, rng: random.Random) -> dict[str, Any]:
        """Mirror of the Java DeterministicInvestigator, with seeded confidence."""
        unsatisfied = context.unsatisfied_mandatory()
        has_contradictions = bool(context.contradictions)
        usable = context.usable_evidence()

        if not context.evidence:
            return {
                "classification": InvestigationClassification.INSUFFICIENT_EVIDENCE,
                "action": RecommendedAction.ACCEPT_LIABILITY,
                "confidence": self._band(rng, _BAND_EMPTY),
                "summary": (
                    f"No evidence is attached to transaction {context.transactionId}, so no "
                    "representment can be supported. A human should decide whether to accept "
                    "liability."
                ),
            }

        if has_contradictions:
            count = len(context.contradictions)
            fields = sorted(
                {c.field for c in context.contradictions if c.field}
            )
            detail = f" on {', '.join(fields)}" if fields else ""
            return {
                "classification": InvestigationClassification.AMBIGUOUS,
                "action": RecommendedAction.ESCALATE_TO_HUMAN,
                "confidence": self._band(rng, _BAND_AMBIGUOUS),
                "summary": (
                    f"{count} contradiction(s){detail} were detected between the merchant "
                    "records. Conflicting evidence undermines a representment more than missing "
                    "evidence does, so a human must resolve the conflict before submission."
                ),
            }

        if unsatisfied:
            names = ", ".join(requirement.type.value for requirement in unsatisfied)
            return {
                "classification": InvestigationClassification.INSUFFICIENT_EVIDENCE,
                "action": RecommendedAction.GATHER_MORE_EVIDENCE,
                "confidence": self._band(rng, _BAND_INSUFFICIENT),
                "summary": (
                    f"{len(unsatisfied)} mandatory requirement(s) are unsatisfied for "
                    f"{context.reasonCode.value if context.reasonCode else 'this reason code'}: "
                    f"{names}. Obtaining them would change the assessment."
                ),
            }

        if not usable:
            return {
                "classification": InvestigationClassification.WEAK,
                "action": RecommendedAction.GATHER_MORE_EVIDENCE,
                "confidence": self._band(rng, _BAND_WEAK),
                "summary": (
                    "Every attached evidence item is expired, invalidated or superseded, so "
                    "nothing currently qualifies as proof."
                ),
            }

        stale = len(context.evidence) - len(usable)
        caveat = (
            f" {stale} superseded or expired item(s) were ignored." if stale else ""
        )
        return {
            "classification": InvestigationClassification.DEFENDABLE,
            "action": RecommendedAction.PREPARE_REPRESENTMENT,
            "confidence": self._band(rng, _BAND_DEFENDABLE),
            "summary": (
                "Every mandatory requirement for "
                f"{context.reasonCode.value if context.reasonCode else 'this reason code'} is "
                f"satisfied by current evidence and no contradictions were detected.{caveat}"
            ),
        }

    @staticmethod
    def _band(rng: random.Random, band: tuple[float, float]) -> float:
        low, high = band
        return round(rng.uniform(low, high), 3)

    # --- evidence selection -------------------------------------------------

    @staticmethod
    def _supporting_evidence(context: InvestigationContext) -> list[str]:
        """Prefer requirement-satisfying evidence; fall back to usable items."""
        ordered: dict[str, None] = {}
        for requirement in context.requirements:
            if requirement.satisfied:
                for evidence_id in requirement.satisfyingEvidenceIds:
                    ordered.setdefault(evidence_id, None)
        if not ordered:
            for item in context.usable_evidence():
                ordered.setdefault(item.evidenceId, None)
        citable = context.evidence_ids()
        return [value for value in ordered if value in citable]

    @staticmethod
    def _citations(context: InvestigationContext, supporting: list[str]) -> list[Citation]:
        """One citation per supported claim, always bound to a context evidence id."""
        by_id = {item.evidenceId: item for item in context.evidence}
        citations: list[Citation] = []
        for requirement in context.requirements:
            if not requirement.satisfied:
                continue
            for evidence_id in requirement.satisfyingEvidenceIds:
                if evidence_id not in supporting:
                    continue
                citations.append(
                    Citation(
                        claim=(
                            f"The {requirement.type.value} requirement is satisfied by "
                            f"evidence {evidence_id}."
                        ),
                        evidenceId=evidence_id,
                    )
                )
        if citations:
            return citations
        for evidence_id in supporting:
            item = by_id.get(evidence_id)
            descriptor = item.type.value if item else "evidence"
            citations.append(
                Citation(
                    claim=f"A {descriptor} record is on file as {evidence_id}.",
                    evidenceId=evidence_id,
                )
            )
        return citations

    @staticmethod
    def _missing_evidence(context: InvestigationContext) -> list[EvidenceType]:
        """Evidence TYPES that would change the assessment - never identifiers."""
        missing: dict[EvidenceType, None] = {}
        for requirement in context.unsatisfied_mandatory():
            missing.setdefault(requirement.type, None)
        for gap in context.gaps:
            if gap.evidenceType is not None:
                missing.setdefault(gap.evidenceType, None)
        return list(missing)

    # --- prose --------------------------------------------------------------

    def _narrative_text(
        self,
        context: InvestigationContext,
        classification: InvestigationClassification,
        supporting: list[str],
        tone: str = "FORMAL",
    ) -> str:
        reason = context.reasonCode.value if context.reasonCode else "an unspecified reason"
        parts: list[str] = [
            f"Transaction {context.transactionId} was disputed under {reason}."
        ]
        if context.disputeAmount is not None:
            parts.append(
                f"The disputed value is {context.disputeAmount.amountMinor} minor units "
                f"{context.disputeAmount.currency}."
            )

        by_id = {item.evidenceId: item for item in context.evidence}
        for requirement in context.requirements:
            if not requirement.satisfied or not requirement.satisfyingEvidenceIds:
                continue
            evidence_id = requirement.satisfyingEvidenceIds[0]
            if evidence_id not in supporting:
                continue
            item = by_id.get(evidence_id)
            when = (
                f", recorded {item.createdAt.date().isoformat()}"
                if item is not None and item.createdAt is not None
                else ""
            )
            parts.append(
                f"The {requirement.type.value} requirement is evidenced by {evidence_id}{when}."
            )

        if context.contradictions:
            details = "; ".join(
                c.detail or f"{c.left} conflicts with {c.right}" for c in context.contradictions
            )
            parts.append(f"Unresolved conflicts remain in the record: {details}.")

        unsatisfied = context.unsatisfied_mandatory()
        if unsatisfied:
            names = ", ".join(requirement.type.value for requirement in unsatisfied)
            parts.append(f"The following mandatory evidence is not on file: {names}.")

        parts.append(f"Assessment: {classification.value}.")
        if tone == "CONCISE":
            return " ".join(parts[:1] + parts[-2:])
        return " ".join(parts)

    # --- accounting ---------------------------------------------------------

    @staticmethod
    def _token_estimate(context: InvestigationContext, output: str) -> tuple[int, int]:
        """Deterministic token estimate: roughly four characters per token.

        Measured against the serialised context rather than the rendered prompt,
        because the rendered prompt contains "hours until deadline" and would
        therefore drift with the wall clock - which would silently break the
        determinism guarantee this class exists to provide.

        Not a real tokenizer count: there is no model call here to measure. The
        numbers are reported so cost dashboards have stable figures in mock
        mode, and they are labelled ``provider=mock`` so nobody mistakes them
        for real usage.
        """
        prompt_chars = len(context.model_dump_json())
        return max(1, prompt_chars // 4), max(1, len(output) // 4)
