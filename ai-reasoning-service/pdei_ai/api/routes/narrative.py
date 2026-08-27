"""``POST /v1/narrative`` (platform contract 8.6).

Returns an evidence-backed representment narrative. ``narrative`` is at the top
level of the response because that is the field
``HttpAiReasoningClient.narrative(...)`` reads; everything else is for the Case
X-Ray UI and the audit trail.

Like ``/v1/investigate``, the route accepts either body shape: a
``NarrativeRequest`` (with tone and length controls) or a bare
``InvestigationContext``, which is what the Java client posts.

Every response has been through ``NarrativeService.enforce_citation_discipline``,
so no evidence id that is absent from the supplied context can reach a reviewer,
in a citation or in the prose.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, status

from pdei_ai.api.deps import NarrativeDep, ProviderOverride, require_service_token
from pdei_ai.models.investigation import InvestigationContext
from pdei_ai.models.narrative import NarrativeRequest, NarrativeResult
from pdei_ai.observability.logging import bind_request_context, clear_request_context, get_logger
from pdei_ai.reasoners.base import ReasonerError, ReasonerUnavailable

log = get_logger(__name__)

router = APIRouter(prefix="/v1", tags=["narrative"], dependencies=[Depends(require_service_token)])


def _parse_body(payload: dict[str, Any]) -> NarrativeRequest:
    """Accept ``{context: ...}`` or a bare ``InvestigationContext``."""
    if "context" in payload and isinstance(payload["context"], dict):
        return NarrativeRequest.model_validate(payload)
    return NarrativeRequest(context=InvestigationContext.model_validate(payload))


@router.post(
    "/narrative",
    response_model=NarrativeResult,
    response_model_exclude_none=True,
    summary="Draft an evidence-backed representment narrative",
)
async def narrative(
    service: NarrativeDep,
    provider: ProviderOverride,
    payload: dict[str, Any] = Body(...),
) -> NarrativeResult:
    try:
        request = _parse_body(payload)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"body must be a NarrativeRequest or an InvestigationContext: {exc}",
        ) from exc

    context = request.context
    bind_request_context(
        investigationId=context.investigationId,
        merchantId=context.merchantId,
        caseId=context.caseId,
    )
    try:
        return await service.narrate(request, provider=provider)
    except (ReasonerError, ReasonerUnavailable) as exc:
        # Same reasoning as /v1/investigate: an empty, honest narrative moves the
        # case to a human; a 5xx makes Temporal retry a provider outage.
        log.warning(
            "narrative generation unavailable",
            investigationId=context.investigationId,
            error=str(exc)[:300],
        )
        from pdei_ai.models.investigation import ModelMetadata

        return NarrativeResult(
            investigationId=context.investigationId,
            narrative="",
            citations=[],
            evidenceIds=[],
            modelMetadata=ModelMetadata(
                provider="deterministic",
                model="pdei-deterministic-v1",
                promptTokens=0,
                completionTokens=0,
                latencyMs=0,
                attempt=1,
            ),
        )
    finally:
        clear_request_context()
