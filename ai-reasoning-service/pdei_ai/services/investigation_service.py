"""Investigation orchestration and the unsupported-claim self-check.

``InvestigationService`` is where a provider's proposal becomes something the
platform is willing to transmit. The pipeline is deliberately short:

    context -> reasoner -> self-check -> result

**The self-check is the point of this file.** Contract 9.3 puts the
authoritative validation on the Java side, against Postgres, and that stays
true - but the Java validator *rejects a whole result* when it finds an
unsupported claim, which routes the case to a human. Filtering here means a
model that got nine claims right and one wrong still contributes its nine, and
the case only escalates when what remains cannot support the conclusion.

The rule enforced: **every claim must cite an evidence id present in the
supplied context.** Anything else is dropped before the result leaves this
process, and each drop increments ``pdei_ai_unsupported_claims_total`` so the
"unsupported claim rate" the project reports is measured, not asserted.

Two consequences follow from dropping claims, and both are applied:

* if every supporting id was unsupported, the result may no longer claim
  ``DEFENDABLE`` - it is downgraded and escalated rather than transmitted;
* confidence is capped once claims have been removed, because a conclusion that
  lost part of its basis is by definition less certain than the model thought.

The service never mutates financial state and never decides anything. It
proposes, annotates and refuses to over-claim.
"""

from __future__ import annotations

import time
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

from pdei_ai.models.common import is_evidence_id, utc_now
from pdei_ai.models.enums import InvestigationClassification, RecommendedAction
from pdei_ai.models.investigation import (
    Citation,
    ContradictionRef,
    InvestigationContext,
    InvestigationResult,
    ModelMetadata,
)
from pdei_ai.observability.logging import get_logger
from pdei_ai.observability.metrics import (
    record_latency,
    record_request,
    record_unsupported_claims,
)
from pdei_ai.observability.tracing import get_tracer
from pdei_ai.reasoners.base import EvidenceReasoner, ReasonerError
from pdei_ai.reasoners.registry import ReasonerRegistry

log = get_logger(__name__)
tracer = get_tracer(__name__)

#: Confidence ceiling applied once any claim has been dropped. Chosen to sit
#: below every plausible ``autoPrepareMinConfidence`` so a result that lost part
#: of its basis can no longer clear an auto-prepare gate on its own.
DEGRADED_CONFIDENCE_CEILING = 0.60


@dataclass
class SelfCheckReport:
    """What the self-check removed, for logging, the API and the audit trail."""

    droppedCitations: list[Citation] = field(default_factory=list)
    droppedSupporting: list[str] = field(default_factory=list)
    droppedContradictions: int = 0
    downgraded: bool = False
    confidenceCapped: bool = False
    notes: list[str] = field(default_factory=list)

    @property
    def dropped_total(self) -> int:
        return len(self.droppedCitations) + len(self.droppedSupporting)

    @property
    def clean(self) -> bool:
        return self.dropped_total == 0 and self.droppedContradictions == 0

    def to_dict(self) -> dict[str, Any]:
        # Counts, never the offending ids: this dict is transmitted on the SSE
        # stream, and a fabricated evidence id must not reach any caller. The
        # ids stay in the structured log.
        return {
            "clean": self.clean,
            "droppedClaims": len(self.droppedCitations),
            "droppedSupportingEvidenceCount": len(self.droppedSupporting),
            "droppedContradictions": self.droppedContradictions,
            "downgraded": self.downgraded,
            "confidenceCapped": self.confidenceCapped,
            "notes": self.notes,
        }


class InvestigationService:
    """Runs an investigation through the active reasoner and self-checks the answer."""

    def __init__(self, registry: ReasonerRegistry) -> None:
        self._registry = registry

    # --- main entry point ---------------------------------------------------

    async def investigate(
        self, context: InvestigationContext, provider: str | None = None
    ) -> InvestigationResult:
        reasoner = self._resolve(provider)
        started = time.perf_counter()

        with tracer.start_as_current_span("pdei.ai.investigate") as span:
            _set_attributes(
                span,
                {
                    "pdei.investigation_id": context.investigationId,
                    "pdei.merchant_id": context.merchantId or "",
                    "pdei.transaction_id": context.transactionId or "",
                    "pdei.provider": reasoner.name,
                    "pdei.evidence_count": len(context.evidence),
                    "pdei.contradiction_count": len(context.contradictions),
                },
            )
            try:
                raw = await reasoner.investigate(context)
            except ReasonerError as exc:
                record_request(reasoner.name, "failure")
                record_latency(reasoner.name, time.perf_counter() - started)
                log.warning(
                    "reasoner failed",
                    provider=reasoner.name,
                    investigationId=context.investigationId,
                    error=str(exc)[:300],
                )
                raise

            result, report = self.self_check(context, raw)
            elapsed = time.perf_counter() - started
            record_request(reasoner.name, "success" if report.clean else "filtered")
            record_latency(reasoner.name, elapsed)
            _set_attributes(
                span,
                {
                    "pdei.classification": result.classification.value,
                    "pdei.confidence": result.confidence,
                    "pdei.dropped_claims": report.dropped_total,
                },
            )

        log.info(
            "investigation complete",
            investigationId=context.investigationId,
            provider=reasoner.name,
            classification=result.classification.value,
            confidence=result.confidence,
            recommendedAction=result.recommendedAction.value,
            droppedClaims=report.dropped_total,
            latencyMs=int(elapsed * 1000),
        )
        return result

    async def investigate_with_report(
        self, context: InvestigationContext, provider: str | None = None
    ) -> tuple[InvestigationResult, SelfCheckReport]:
        """Same as ``investigate`` but also returns the self-check report.

        Used by the SSE stream, which shows the operator exactly what was
        filtered - the filtering is a feature, so it should be visible.
        """
        reasoner = self._resolve(provider)
        started = time.perf_counter()
        raw = await reasoner.investigate(context)
        result, report = self.self_check(context, raw)
        record_request(reasoner.name, "success" if report.clean else "filtered")
        record_latency(reasoner.name, time.perf_counter() - started)
        return result, report

    # --- the self-check -----------------------------------------------------

    def self_check(
        self, context: InvestigationContext, result: InvestigationResult
    ) -> tuple[InvestigationResult, SelfCheckReport]:
        """Drop every claim not supported by the supplied context.

        Pure and synchronous so it can be unit tested against hand-built results
        without a reasoner in the way.
        """
        citable = context.evidence_ids()
        report = SelfCheckReport()

        kept_citations: list[Citation] = []
        for citation in result.citations:
            if citation.evidenceId in citable:
                kept_citations.append(citation)
            else:
                report.droppedCitations.append(citation)

        kept_supporting: list[str] = []
        for evidence_id in result.supportingEvidence:
            if evidence_id in citable:
                kept_supporting.append(evidence_id)
            else:
                report.droppedSupporting.append(evidence_id)

        kept_contradictions: list[ContradictionRef] = []
        for contradiction in result.contradictions:
            referenced = contradiction.referenced_evidence_ids()
            if referenced and not all(value in citable for value in referenced):
                report.droppedContradictions += 1
                continue
            kept_contradictions.append(contradiction)

        if report.droppedCitations:
            report.notes.append(
                f"{len(report.droppedCitations)} claim(s) cited evidence absent from the "
                "context and were removed"
            )
        if report.droppedSupporting:
            # The invented ids are counted here but deliberately NOT named: the
            # note travels in reasoningSummary, and echoing a fabricated
            # evidence id into the transmitted result is exactly what this
            # filter exists to prevent. The ids go to the structured log below,
            # where an operator can see them without them reaching a reviewer.
            report.notes.append(
                f"{len(report.droppedSupporting)} supporting evidence reference(s) not "
                "present in the context were removed"
            )
        if report.droppedContradictions:
            report.notes.append(
                f"{report.droppedContradictions} contradiction(s) referenced unknown evidence "
                "and were removed"
            )

        record_unsupported_claims(report.dropped_total)
        if not report.clean:
            log.warning(
                "unsupported claims filtered",
                investigationId=context.investigationId,
                provider=result.modelMetadata.provider,
                droppedClaims=len(report.droppedCitations),
                droppedSupporting=report.droppedSupporting,
                invented=[value for value in report.droppedSupporting if is_evidence_id(value)],
            )

        classification = result.classification
        action = result.recommendedAction
        confidence = result.confidence
        summary = result.reasoningSummary

        # A conclusion that lost its entire basis is no longer that conclusion.
        if not kept_supporting and classification is InvestigationClassification.DEFENDABLE:
            classification = InvestigationClassification.INSUFFICIENT_EVIDENCE
            action = RecommendedAction.ESCALATE_TO_HUMAN
            report.downgraded = True
            report.notes.append(
                "classification downgraded from DEFENDABLE: no supporting evidence survived "
                "the citation check"
            )

        if not report.clean and confidence > DEGRADED_CONFIDENCE_CEILING:
            confidence = DEGRADED_CONFIDENCE_CEILING
            report.confidenceCapped = True
            report.notes.append(
                f"confidence capped at {DEGRADED_CONFIDENCE_CEILING} because part of the "
                "reasoning basis was removed"
            )

        if report.notes:
            appended = " [self-check: " + "; ".join(report.notes) + "]"
            summary = (summary or "").rstrip() + appended

        checked = result.model_copy(
            update={
                "classification": classification,
                "recommendedAction": action,
                "confidence": confidence,
                "supportingEvidence": kept_supporting,
                "citations": kept_citations,
                "contradictions": kept_contradictions,
                "reasoningSummary": summary,
            }
        )
        return checked, report

    # --- streaming ----------------------------------------------------------

    async def investigate_stream(
        self, context: InvestigationContext, provider: str | None = None
    ) -> AsyncIterator[dict[str, Any]]:
        """Yield SSE step payloads for ``POST /v1/investigate/stream``.

        The stream exists so an operator can watch the reasoning happen instead
        of staring at a spinner, and so the Case X-Ray page can show the same
        steps the audit trail will later record.
        """
        reasoner = self._resolve(provider)
        started = time.perf_counter()

        yield _step(
            "accepted",
            "investigation accepted",
            {
                "investigationId": context.investigationId,
                "caseId": context.caseId,
                "transactionId": context.transactionId,
            },
        )

        yield _step(
            "context",
            "curated context summarised",
            {
                "evidenceCount": len(context.evidence),
                "usableEvidenceCount": len(context.usable_evidence()),
                "requirementCount": len(context.requirements),
                "unsatisfiedMandatory": [
                    requirement.type.value for requirement in context.unsatisfied_mandatory()
                ],
                "gapCount": len(context.gaps),
                "contradictionCount": len(context.contradictions),
                "citableEvidenceIds": sorted(context.evidence_ids()),
            },
        )

        yield _step(
            "provider",
            "reasoner selected",
            {
                "provider": reasoner.name,
                "model": getattr(reasoner, "model", "unknown"),
                "fallbackChain": self._registry.chain,
            },
        )

        try:
            raw = await reasoner.investigate(context)
        except ReasonerError as exc:
            record_request(reasoner.name, "failure")
            yield _step("error", "reasoner failed", {"error": str(exc)[:500]})
            return

        yield _step(
            "reasoning",
            "provider returned a proposal",
            {
                "classification": raw.classification.value,
                "confidence": raw.confidence,
                "citationCount": len(raw.citations),
                "supportingEvidence": raw.supportingEvidence,
            },
        )

        result, report = self.self_check(context, raw)
        yield _step("self_check", "claims verified against the supplied context", report.to_dict())

        elapsed = time.perf_counter() - started
        record_request(reasoner.name, "success" if report.clean else "filtered")
        record_latency(reasoner.name, elapsed)

        yield _step("result", "final result", result.to_wire())
        yield _step("done", "stream complete", {"latencyMs": int(elapsed * 1000)})

    # --- helpers ------------------------------------------------------------

    def _resolve(self, provider: str | None) -> EvidenceReasoner:
        if provider:
            return self._registry.get(provider)
        return self._registry.active

    @staticmethod
    def deterministic_placeholder(
        context: InvestigationContext, detail: str
    ) -> InvestigationResult:
        """A safe, fully cited answer for when no provider could be reached.

        Mirrors the Java ``DeterministicInvestigator`` fallback so an outage
        degrades the quality of dispute handling rather than stopping it. Tagged
        ``provider="deterministic"`` so the UI, audit trail and funnel metrics
        can always tell it apart from a model answer.
        """
        supporting = [item.evidenceId for item in context.usable_evidence()]
        return InvestigationResult(
            investigationId=context.investigationId,
            classification=InvestigationClassification.AMBIGUOUS,
            confidence=0.0,
            supportingEvidence=supporting,
            missingEvidence=[requirement.type for requirement in context.unsatisfied_mandatory()],
            contradictions=list(context.contradictions),
            reasoningSummary=(
                f"No AI provider was available ({detail}). This case is escalated to a human; "
                "the deterministic evidence set is unchanged."
            ),
            narrative="",
            recommendedAction=RecommendedAction.ESCALATE_TO_HUMAN,
            citations=[
                Citation(
                    claim=f"Evidence {evidence_id} is attached to this transaction.",
                    evidenceId=evidence_id,
                )
                for evidence_id in supporting
            ],
            modelMetadata=ModelMetadata(
                provider="deterministic",
                model="pdei-deterministic-v1",
                promptTokens=0,
                completionTokens=0,
                latencyMs=0,
                attempt=1,
            ),
        )


def _step(step: str, message: str, detail: dict[str, Any]) -> dict[str, Any]:
    return {
        "step": step,
        "message": message,
        "at": utc_now().isoformat().replace("+00:00", "Z"),
        "detail": detail,
    }


def _set_attributes(span: Any, attributes: dict[str, Any]) -> None:
    try:
        for key, value in attributes.items():
            span.set_attribute(key, value)
    except Exception:  # pragma: no cover - tracing never breaks reasoning
        pass
