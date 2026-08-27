"""HTTP routes - exactly the surface of platform contract 8.6, nothing more.

    GET  /health
    GET  /ready
    POST /v1/investigate
    POST /v1/investigate/stream
    POST /v1/admission/score
    POST /v1/narrative
    GET  /v1/tools
    GET  /v1/providers
    GET  /metrics                (mounted in main.py)

New routes need a contract change first. An endpoint that exists in the code but
not in ``docs/PLATFORM-CONTRACT.md`` is a divergence, even a harmless-looking one.
"""

from fastapi import APIRouter

from pdei_ai.api.routes import admission, health, investigate, narrative, providers, tools

#: Everything under ``/v1``. Mounted by ``pdei_ai.main``.
v1_router = APIRouter()
v1_router.include_router(investigate.router)
v1_router.include_router(admission.router)
v1_router.include_router(narrative.router)
v1_router.include_router(tools.router)
v1_router.include_router(providers.router)

#: Probes, deliberately outside ``/v1`` and outside the service-token guard.
health_router = health.router

__all__ = ["health_router", "v1_router"]
