"""Request dependencies.

Everything expensive - the httpx client, the reasoner registry, the Redis budget
gate - is constructed once in the FastAPI lifespan and hung off ``app.state``.
These helpers read it back. No module-level singletons: a test can build an app
with its own state and get complete isolation.
"""

from __future__ import annotations

from typing import Annotated, Any

from fastapi import Depends, Header, HTTPException, Request, status

from pdei_ai.config import Settings
from pdei_ai.reasoners.registry import ReasonerRegistry
from pdei_ai.services.admission_service import AdmissionService
from pdei_ai.services.budget import BudgetGate
from pdei_ai.services.investigation_service import InvestigationService
from pdei_ai.services.narrative_service import NarrativeService
from pdei_ai.tools.client import SERVICE_TOKEN_HEADER
from pdei_ai.tools.executor import ToolExecutor


def get_settings(request: Request) -> Settings:
    return request.app.state.settings  # type: ignore[no-any-return]


def get_registry(request: Request) -> ReasonerRegistry:
    return request.app.state.registry  # type: ignore[no-any-return]


def get_investigation_service(request: Request) -> InvestigationService:
    return request.app.state.investigation_service  # type: ignore[no-any-return]


def get_admission_service(request: Request) -> AdmissionService:
    return request.app.state.admission_service  # type: ignore[no-any-return]


def get_narrative_service(request: Request) -> NarrativeService:
    return request.app.state.narrative_service  # type: ignore[no-any-return]


def get_budget(request: Request) -> BudgetGate | None:
    return getattr(request.app.state, "budget", None)


def get_tool_executor(request: Request) -> ToolExecutor | None:
    return getattr(request.app.state, "tool_executor", None)


async def require_service_token(
    request: Request,
    x_pdei_service_token: Annotated[str | None, Header(alias=SERVICE_TOKEN_HEADER)] = None,
) -> None:
    """Shared-secret check on ``/v1/*``.

    Off by default so the stack runs locally with no configuration
    (``PDEI_AI_REQUIRE_SERVICE_TOKEN=false``). When on, the same token the AI
    service presents to the gateway is required in the other direction, which
    keeps one secret in play rather than two.

    Note what this is not: it is not authorisation. Nothing behind this header
    can change financial state, so the token protects model budget and curated
    context, not money.
    """
    settings: Settings = request.app.state.settings
    if not settings.require_service_token:
        return
    expected = settings.service_token
    if not x_pdei_service_token or x_pdei_service_token != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"missing or invalid {SERVICE_TOKEN_HEADER}",
        )


def provider_override(
    x_pdei_provider: Annotated[str | None, Header(alias="X-PDEI-Provider")] = None,
) -> str | None:
    """Optional per-request provider override.

    Lets an operator compare ``mock`` and ``gemini`` on the same case without a
    redeploy - useful in a demo, and harmless because the registry only returns
    providers that were configured to exist.
    """
    return x_pdei_provider


SettingsDep = Annotated[Settings, Depends(get_settings)]
RegistryDep = Annotated[ReasonerRegistry, Depends(get_registry)]
InvestigationDep = Annotated[InvestigationService, Depends(get_investigation_service)]
AdmissionDep = Annotated[AdmissionService, Depends(get_admission_service)]
NarrativeDep = Annotated[NarrativeService, Depends(get_narrative_service)]
BudgetDep = Annotated["BudgetGate | None", Depends(get_budget)]
ToolExecutorDep = Annotated["ToolExecutor | None", Depends(get_tool_executor)]
ProviderOverride = Annotated[str | None, Depends(provider_override)]
ServiceTokenGuard = Depends(require_service_token)


def state_snapshot(request: Request) -> dict[str, Any]:
    """Everything ``/ready`` needs, without reaching into app.state everywhere."""
    state = request.app.state
    return {
        "settings": getattr(state, "settings", None),
        "registry": getattr(state, "registry", None),
        "budget": getattr(state, "budget", None),
        "toolsClient": getattr(state, "tools_client", None),
    }
