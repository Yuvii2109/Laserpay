"""``POST /v1/admission/score`` (platform contract 8.6 / 9.4).

Returns ``{admit, priority, reason}`` plus the individual terms as diagnostics.

**Advisory only.** The Java ``AdmissionController`` owns the real decision, and
``AdmissionScore`` on that side reads only the three contract fields. Admission
control is a cost and safety gate, so it must not depend on the service it
throttles - if this endpoint is down, Temporal still decides correctly.

The route accepts either body shape: a compact ``AdmissionRequest`` (what the
simulator and tests post) or a full ``InvestigationContext`` (what
``HttpAiReasoningClient`` posts). Normalisation happens in
``admission_request_from_payload``.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, status

from pdei_ai.api.deps import AdmissionDep, require_service_token
from pdei_ai.models.admission import AdmissionDecision, admission_request_from_payload
from pdei_ai.observability.logging import get_logger

log = get_logger(__name__)

router = APIRouter(prefix="/v1", tags=["admission"], dependencies=[Depends(require_service_token)])


@router.post(
    "/admission/score",
    response_model=AdmissionDecision,
    response_model_exclude_none=True,
    summary="Score a case for AI admission (advisory; Java owns the decision)",
)
async def score(
    service: AdmissionDep,
    payload: dict[str, Any] = Body(...),
) -> AdmissionDecision:
    try:
        request = admission_request_from_payload(payload)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"body must be an AdmissionRequest or an InvestigationContext: {exc}",
        ) from exc

    return await service.decide(request)


# Budget and token-bucket diagnostics deliberately live on GET /v1/providers
# rather than on a route of their own: contract 8.6 fixes the endpoint list for
# this service, and adding to it would be a divergence for the sake of a
# dashboard panel.
