"""``NullReasoner`` - always abstains (contract 9.5).

Selected with ``PDEI_AI_PROVIDER=null``. It answers every investigation the same
way: no classification it can stand behind, zero confidence, no citations, and
``ESCALATE_TO_HUMAN``.

This is not a stub. Abstention is a legitimate operating mode:

* it proves the platform functions with AI switched entirely off - useful when
  demonstrating that the deterministic path carries the system;
* it is the correct configuration when a provider is compromised, over budget
  or under review, without redeploying anything;
* it gives the safety story a control: the abstaining provider must never cause
  a case to be auto-prepared, and confidence 0.0 with ``ESCALATE_TO_HUMAN``
  fails every auto-prepare gate in contract 9.3 by construction.

An abstention is deliberately distinguishable from a failure: it returns a valid
result, so the case moves to a human rather than into a retry loop.
"""

from __future__ import annotations

from pdei_ai.models.enums import InvestigationClassification, RecommendedAction
from pdei_ai.models.investigation import InvestigationContext, InvestigationResult, ModelMetadata
from pdei_ai.models.narrative import NarrativeRequest, NarrativeResult
from pdei_ai.reasoners.base import BaseReasoner, ReasonerHealth

PROVIDER_NAME = "null"
NULL_MODEL = "pdei-null-abstain"

ABSTENTION_SUMMARY = (
    "AI reasoning is disabled for this deployment (PDEI_AI_PROVIDER=null). No model examined "
    "this case, so no classification is asserted and no evidence is cited. The deterministic "
    "readiness and policy layers remain fully in effect; this case is routed to a human."
)

ABSTENTION_NARRATIVE = (
    "No AI-generated narrative is available: reasoning is disabled for this deployment. "
    "The representment must be drafted from the deterministic evidence package."
)


class NullReasoner(BaseReasoner):
    """A reasoner that declines to reason, safely and consistently."""

    name = PROVIDER_NAME
    model = NULL_MODEL

    async def investigate(self, context: InvestigationContext) -> InvestigationResult:
        return InvestigationResult(
            investigationId=context.investigationId,
            classification=InvestigationClassification.AMBIGUOUS,
            confidence=0.0,
            supportingEvidence=[],
            missingEvidence=[],
            # Contradictions are deterministic facts detected by evidence-core,
            # not model output, so passing them through is not an assertion.
            contradictions=list(context.contradictions),
            reasoningSummary=ABSTENTION_SUMMARY,
            narrative=ABSTENTION_NARRATIVE,
            recommendedAction=RecommendedAction.ESCALATE_TO_HUMAN,
            citations=[],
            modelMetadata=ModelMetadata(
                provider=self.name,
                model=self.model,
                promptTokens=0,
                completionTokens=0,
                latencyMs=0,
                attempt=1,
            ),
        )

    async def narrate(self, request: NarrativeRequest) -> NarrativeResult:
        return NarrativeResult(
            investigationId=request.context.investigationId,
            narrative=ABSTENTION_NARRATIVE,
            citations=[],
            evidenceIds=[],
            modelMetadata=ModelMetadata(
                provider=self.name,
                model=self.model,
                promptTokens=0,
                completionTokens=0,
                latencyMs=0,
                attempt=1,
            ),
        )

    async def health(self) -> ReasonerHealth:
        # Healthy: it is doing exactly what it was configured to do. Reporting
        # unhealthy would make the registry fall through to another provider,
        # which is the opposite of what selecting "null" means.
        return ReasonerHealth(
            provider=self.name,
            model=self.model,
            healthy=True,
            detail="abstaining provider; every investigation is escalated to a human",
        )
