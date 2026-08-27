"""``GET /v1/providers`` (platform contract 8.6) - active reasoner and fallback chain.

Answers the question an operator most needs answered during a demo: *which
provider is actually running?* Silent degradation from ``gemini`` to ``mock``
would make every conclusion drawn from a demo wrong, so the requested provider,
the selected provider, the chain that was walked and the reason any provider is
unavailable are all reported explicitly.

Budget and token-bucket state is reported here too, rather than on a route of
its own, because contract 8.6 fixes the endpoint list for this service.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

from pdei_ai.api.deps import BudgetDep, RegistryDep, SettingsDep, require_service_token
from pdei_ai.prompts.system import SYSTEM_PROMPT_VERSION

router = APIRouter(prefix="/v1", tags=["providers"], dependencies=[Depends(require_service_token)])


@router.get("/providers", summary="Active reasoner, fallback chain and budget state")
async def providers(
    registry: RegistryDep,
    settings: SettingsDep,
    budget: BudgetDep,
) -> dict[str, Any]:
    described = registry.describe()
    health = [report.to_wire() for report in await registry.health()]

    payload: dict[str, Any] = {
        **described,
        "health": health,
        "promptVersion": SYSTEM_PROMPT_VERSION,
        "configuration": settings.redacted(),
        "fallbackPolicy": (
            "The requested provider is tried first, then PDEI_AI_FALLBACK_CHAIN in order, "
            "then mock as the terminal fallback. 'null' is never a fallback target: "
            "abstention is chosen deliberately, never drifted into."
        ),
    }

    if budget is None:
        payload["budget"] = {"enabled": False, "detail": "no Redis budget gate configured"}
    else:
        payload["budget"] = {"enabled": True, **await budget.status()}

    return payload
