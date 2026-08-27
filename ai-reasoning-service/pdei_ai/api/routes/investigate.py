"""``POST /v1/investigate`` and ``POST /v1/investigate/stream`` (contract 8.6).

The non-streaming route is what Temporal's ``investigate`` activity calls
through ``HttpAiReasoningClient``. The streaming variant emits the same work as
Server-Sent Events so the Case X-Ray page can show reasoning as it happens.

Both go through ``InvestigationService``, so both get the unsupported-claim
self-check. There is no path from a provider to a caller that skips it.

**Failure behaviour.** When every provider fails, the route answers 200 with a
deterministic placeholder rather than 5xx. A Temporal activity that receives an
error retries, and retrying a model outage burns the retry budget of a case that
has a deadline. A valid ``ESCALATE_TO_HUMAN`` result, clearly tagged
``provider="deterministic"``, moves the case forward instead.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator

from fastapi import APIRouter, Body, Depends
from fastapi.responses import StreamingResponse

from pdei_ai.api.deps import (
    InvestigationDep,
    ProviderOverride,
    RegistryDep,
    require_service_token,
)
from pdei_ai.models.investigation import InvestigationContext, InvestigationResult
from pdei_ai.observability.logging import bind_request_context, clear_request_context, get_logger
from pdei_ai.reasoners.base import ReasonerError, ReasonerUnavailable
from pdei_ai.services.investigation_service import InvestigationService

log = get_logger(__name__)

router = APIRouter(
    prefix="/v1", tags=["investigate"], dependencies=[Depends(require_service_token)]
)


@router.post(
    "/investigate",
    response_model=InvestigationResult,
    response_model_exclude_none=True,
    summary="Investigate one dispute from a curated context",
)
async def investigate(
    service: InvestigationDep,
    provider: ProviderOverride,
    context: InvestigationContext = Body(...),
) -> InvestigationResult:
    bind_request_context(
        investigationId=context.investigationId,
        merchantId=context.merchantId,
        caseId=context.caseId,
    )
    try:
        return await service.investigate(context, provider=provider)
    except (ReasonerError, ReasonerUnavailable) as exc:
        log.warning(
            "all providers failed; returning the deterministic placeholder",
            investigationId=context.investigationId,
            error=str(exc)[:300],
        )
        return InvestigationService.deterministic_placeholder(context, str(exc)[:200])
    finally:
        clear_request_context()


@router.post(
    "/investigate/stream",
    summary="Investigate one dispute, streaming each step as SSE",
    response_class=StreamingResponse,
)
async def investigate_stream(
    service: InvestigationDep,
    registry: RegistryDep,
    provider: ProviderOverride,
    context: InvestigationContext = Body(...),
) -> StreamingResponse:
    """SSE step stream.

    Frames are ``event: <step>`` plus a JSON ``data:`` line, which is what the
    browser ``EventSource`` API and the Next.js client both expect. The final
    frames are always ``result`` then ``done`` (or ``error`` then ``done``), so
    a consumer always knows the stream finished rather than dropped.
    """
    bind_request_context(
        investigationId=context.investigationId,
        merchantId=context.merchantId,
        caseId=context.caseId,
    )

    async def event_stream() -> AsyncIterator[bytes]:
        try:
            async for step in service.investigate_stream(context, provider=provider):
                yield _sse(step["step"], step)
        except (ReasonerError, ReasonerUnavailable) as exc:
            fallback = InvestigationService.deterministic_placeholder(context, str(exc)[:200])
            yield _sse("error", {"step": "error", "detail": {"error": str(exc)[:300]}})
            yield _sse("result", {"step": "result", "detail": fallback.to_wire()})
            yield _sse("done", {"step": "done", "detail": {"degraded": True}})
        except Exception as exc:  # pragma: no cover - defensive
            log.error("investigation stream failed", error=str(exc)[:300])
            yield _sse("error", {"step": "error", "detail": {"error": "internal error"}})
            yield _sse("done", {"step": "done", "detail": {"degraded": True}})
        finally:
            clear_request_context()

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "Connection": "keep-alive",
            # Proxies that buffer would defeat the point of streaming.
            "X-Accel-Buffering": "no",
            "X-PDEI-Provider": registry.active_name,
        },
    )


def _sse(event: str, payload: dict[str, object]) -> bytes:
    body = json.dumps(payload, default=str)
    return f"event: {event}\ndata: {body}\n\n".encode()
