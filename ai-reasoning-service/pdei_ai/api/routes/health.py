"""``GET /health`` and ``GET /ready`` (platform contract 8.6).

The distinction matters to Kubernetes and to docker-compose healthchecks:

* ``/health`` is **liveness**. If the process can answer, it is alive. It never
  checks a dependency - a Redis outage must not get the container killed.
* ``/ready`` is **readiness**. It reports whether this replica can usefully
  serve traffic right now: a reasoner is selected and healthy, and the optional
  dependencies (Redis budget, gateway tool surface) are described honestly.

``/ready`` returns 503 only when there is no usable reasoner. Redis and the tool
surface being down degrades quality, not capability: the mock reasoner needs
neither, and the fallback chain guarantees some provider is always present.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request, Response, status

from pdei_ai import SERVICE_NAME, __version__
from pdei_ai.models.common import utc_now
from pdei_ai.prompts.system import SYSTEM_PROMPT_VERSION

router = APIRouter(tags=["health"])


@router.get("/health", summary="Liveness probe")
async def health() -> dict[str, Any]:
    """Alive. Deliberately dependency-free."""
    return {
        "status": "UP",
        "service": SERVICE_NAME,
        "version": __version__,
        "at": utc_now().isoformat().replace("+00:00", "Z"),
    }


@router.get("/ready", summary="Readiness probe")
async def ready(request: Request, response: Response) -> dict[str, Any]:
    """Ready when a reasoner is selected and healthy."""
    state = request.app.state
    registry = getattr(state, "registry", None)
    settings = getattr(state, "settings", None)

    checks: dict[str, Any] = {}
    ready_flag = True

    # --- reasoner (required) ------------------------------------------------
    if registry is None:
        checks["reasoner"] = {"status": "DOWN", "detail": "registry not initialised"}
        ready_flag = False
    else:
        health_reports = await registry.health()
        active = next(
            (report for report in health_reports if report.provider == registry.active_name),
            None,
        )
        healthy = bool(active and active.healthy)
        checks["reasoner"] = {
            "status": "UP" if healthy else "DOWN",
            "provider": registry.active_name,
            "model": getattr(registry.active, "model", "unknown"),
            "detail": active.detail if active else "no health report",
        }
        ready_flag = ready_flag and healthy

    # --- Redis budget (optional) -------------------------------------------
    budget = getattr(state, "budget", None)
    if budget is None:
        checks["budget"] = {"status": "DISABLED", "detail": "no Redis configured"}
    else:
        connected = await budget.ping()
        checks["budget"] = {
            "status": "UP" if connected else "DEGRADED",
            "detail": (
                "redis reachable"
                if connected
                else f"redis unreachable; failOpen={budget.fail_open}"
            ),
        }

    # --- gateway tool surface (optional) ------------------------------------
    tools_client = getattr(state, "tools_client", None)
    if tools_client is None:
        checks["tools"] = {"status": "DISABLED", "detail": "tool layer not configured"}
    else:
        reachable = await tools_client.ping()
        checks["tools"] = {
            "status": "UP" if reachable else "DEGRADED",
            "detail": (
                "api-gateway-service reachable"
                if reachable
                else "api-gateway-service unreachable; the model answers from context alone"
            ),
        }

    if not ready_flag:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE

    return {
        "status": "UP" if ready_flag else "DOWN",
        "service": SERVICE_NAME,
        "version": __version__,
        "promptVersion": SYSTEM_PROMPT_VERSION,
        "provider": getattr(registry, "active_name", None),
        "requestedProvider": getattr(settings, "provider", None),
        "checks": checks,
        "at": utc_now().isoformat().replace("+00:00", "Z"),
    }
