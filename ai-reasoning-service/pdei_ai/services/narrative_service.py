"""Narrative generation with two layers of citation discipline.

Prose is the riskiest thing this service emits. A structured result can be
validated field by field; a paragraph can smuggle a fabricated tracking number
past every schema check ever written. So the narrative goes through two filters
before it leaves this process:

1. **Citation filter** - identical in spirit to the investigation self-check:
   any citation whose evidence id is absent from the supplied context is
   dropped and counted in ``pdei_ai_unsupported_claims_total``.
2. **Reference redaction** - the prose itself is scanned for ``EV-`` tokens. Any
   token not present in the context is replaced with an explicit redaction
   marker rather than left in place. A reviewer seeing
   ``[unsupported evidence reference removed]`` learns something true; a
   reviewer seeing ``EV-9999`` learns something false.

Redaction is deliberately visible rather than silent: an operator should be able
to tell that the model tried to cite something that does not exist.
"""

from __future__ import annotations

import re
import time

from pdei_ai.models.common import is_evidence_id
from pdei_ai.models.investigation import Citation, InvestigationContext
from pdei_ai.models.narrative import NarrativeRequest, NarrativeResult
from pdei_ai.observability.logging import get_logger
from pdei_ai.observability.metrics import (
    record_latency,
    record_request,
    record_unsupported_claims,
)
from pdei_ai.reasoners.base import EvidenceReasoner, ReasonerError
from pdei_ai.reasoners.registry import ReasonerRegistry

log = get_logger(__name__)

REDACTION_MARKER = "[unsupported evidence reference removed]"
_EVIDENCE_TOKEN_RE = re.compile(r"\bEV-[A-Za-z0-9._:-]{1,60}\b")


class NarrativeService:
    """Produces an evidence-backed representment narrative."""

    def __init__(self, registry: ReasonerRegistry) -> None:
        self._registry = registry

    async def narrate(
        self, request: NarrativeRequest, provider: str | None = None
    ) -> NarrativeResult:
        reasoner = self._resolve(provider)
        started = time.perf_counter()

        try:
            raw = await reasoner.narrate(request)
        except ReasonerError as exc:
            record_request(reasoner.name, "failure")
            log.warning(
                "narrative generation failed",
                provider=reasoner.name,
                investigationId=request.context.investigationId,
                error=str(exc)[:300],
            )
            raise

        result = self.enforce_citation_discipline(request.context, raw)
        elapsed = time.perf_counter() - started
        record_request(
            reasoner.name,
            "success" if result.droppedClaims == 0 and result.redactedReferences == 0
            else "filtered",
        )
        record_latency(reasoner.name, elapsed)

        log.info(
            "narrative complete",
            investigationId=request.context.investigationId,
            provider=reasoner.name,
            words=result.word_count,
            citations=len(result.citations),
            droppedClaims=result.droppedClaims,
            redactedReferences=result.redactedReferences,
        )
        return result

    # --- filters ------------------------------------------------------------

    def enforce_citation_discipline(
        self, context: InvestigationContext, result: NarrativeResult
    ) -> NarrativeResult:
        """Drop unsupported citations and redact unsupported ids from the prose.

        Pure and synchronous, so it can be tested against a hand-built narrative
        without a reasoner in the way.
        """
        citable = context.evidence_ids()

        kept: list[Citation] = []
        dropped = 0
        for citation in result.citations:
            if citation.evidenceId in citable:
                kept.append(citation)
            else:
                dropped += 1

        text, redacted = self.redact_unsupported_references(result.narrative, citable)

        if dropped or redacted:
            record_unsupported_claims(dropped + redacted)
            log.warning(
                "narrative claims filtered",
                investigationId=context.investigationId,
                droppedCitations=dropped,
                redactedReferences=redacted,
            )

        evidence_ids = list(dict.fromkeys(citation.evidenceId for citation in kept))
        return result.model_copy(
            update={
                "narrative": text,
                "citations": kept,
                "evidenceIds": evidence_ids,
                "droppedClaims": dropped,
                "redactedReferences": redacted,
            }
        )

    @staticmethod
    def redact_unsupported_references(text: str, citable: set[str]) -> tuple[str, int]:
        """Replace ``EV-`` tokens absent from the context with a visible marker."""
        if not text:
            return "", 0
        redacted = 0

        def replace(match: re.Match[str]) -> str:
            nonlocal redacted
            token = match.group(0)
            if token in citable or not is_evidence_id(token):
                return token
            redacted += 1
            return REDACTION_MARKER

        return _EVIDENCE_TOKEN_RE.sub(replace, text), redacted

    # --- helpers ------------------------------------------------------------

    def _resolve(self, provider: str | None) -> EvidenceReasoner:
        if provider:
            return self._registry.get(provider)
        return self._registry.active
