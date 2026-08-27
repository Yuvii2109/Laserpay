"""FastAPI application for ``ai-reasoning-service`` (port 8000, contract section 2).

Everything expensive is built once in the lifespan and stored on ``app.state``:
the httpx client for the read-only tool surface, the tool executor, the reasoner
registry (which walks the fallback chain at startup), the Redis budget gate, and
the three services.

Build order matters and is not arbitrary: the tool executor must exist before
the registry, because ``GeminiReasoner`` receives it at construction. Building
the registry at startup rather than per request also means provider selection is
logged once, deterministically, instead of being re-decided under load.

``create_app()`` takes optional overrides so tests can inject a fake reasoner or
a stub Redis and get a fully wired app with no network at all.
"""

from __future__ import annotations

import contextlib
from collections.abc import AsyncIterator
from typing import Any

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from pdei_ai import SERVICE_NAME, __version__
from pdei_ai.api.routes import health_router, v1_router
from pdei_ai.config import Settings
from pdei_ai.observability.logging import configure_logging, get_logger
from pdei_ai.observability.tracing import (
    configure_tracing,
    instrument_fastapi,
    shutdown_tracing,
)
from pdei_ai.reasoners.base import EvidenceReasoner
from pdei_ai.reasoners.registry import build_registry
from pdei_ai.services.admission_service import AdmissionService
from pdei_ai.services.budget import BudgetGate
from pdei_ai.services.investigation_service import InvestigationService
from pdei_ai.services.narrative_service import NarrativeService
from pdei_ai.tools.client import AiToolsClient
from pdei_ai.tools.executor import ToolExecutor

log = get_logger(__name__)

DESCRIPTION = """\
The AI reasoning boundary of PDEI (Pre-Dispute Evidence Intelligence).

This service is the only place in the platform where AI model code lives. It
receives a curated `InvestigationContext`, may widen its view through ten
read-only tools on `api-gateway-service`, and returns a schema-constrained
`InvestigationResult`.

It has no database access and no authority over financial state. Every claim it
returns cites an evidence id that was present in the supplied context; anything
else is dropped before the response leaves the process. A deterministic Java
policy gate then decides whether the proposal may be acted on at all.
"""


def create_app(
    settings: Settings | None = None,
    reasoner_overrides: dict[str, EvidenceReasoner] | None = None,
    budget: BudgetGate | None = None,
    tools_client: AiToolsClient | None = None,
) -> FastAPI:
    """Build the application. Overrides exist so tests can wire it without network."""
    resolved = settings or Settings.from_env()
    configure_logging(resolved.log_level, resolved.otel_service_name)
    for warning in resolved.startup_warnings:
        log.warning("configuration warning", detail=warning)

    @contextlib.asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        application.state.settings = resolved

        configure_tracing(
            service_name=resolved.otel_service_name,
            otlp_endpoint=resolved.otlp_endpoint,
            enabled=resolved.tracing_enabled,
            service_version=__version__,
        )

        # --- read-only tool surface ----------------------------------------
        client = tools_client
        if client is None and resolved.tools_enabled:
            client = AiToolsClient(
                base_url=resolved.api_base_url,
                service_token=resolved.service_token,
                timeout_seconds=resolved.tool_timeout_seconds,
                max_retries=resolved.tool_max_retries,
            )
        application.state.tools_client = client
        executor = ToolExecutor(client, max_calls=resolved.max_tool_calls)
        application.state.tool_executor = executor

        # --- providers (walks the fallback chain once, and logs it) ---------
        registry = build_registry(resolved, tool_executor=executor, overrides=reasoner_overrides)
        application.state.registry = registry

        # --- cost control ---------------------------------------------------
        gate = budget
        if gate is None and resolved.redis_url:
            gate = BudgetGate(
                redis_url=resolved.redis_url,
                daily_budget=resolved.daily_budget,
                bucket_capacity=resolved.bucket_capacity,
                refill_per_second=resolved.bucket_refill_per_second,
                fail_open=resolved.budget_fail_open,
            )
        application.state.budget = gate

        # --- services --------------------------------------------------------
        application.state.investigation_service = InvestigationService(registry)
        application.state.narrative_service = NarrativeService(registry)
        application.state.admission_service = AdmissionService(
            budget=gate,
            priority_threshold=resolved.admission_threshold,
            financial_impact_cap_minor=resolved.financial_impact_cap_minor,
            ambiguity_cap=resolved.ambiguity_cap,
        )

        log.info(
            "ai-reasoning-service started",
            version=__version__,
            port=resolved.port,
            provider=registry.active_name,
            chain=registry.chain,
            toolsEnabled=bool(client is not None),
        )

        try:
            yield
        finally:
            if client is not None:
                await client.aclose()
            if gate is not None:
                await gate.aclose()
            shutdown_tracing()
            log.info("ai-reasoning-service stopped")

    app = FastAPI(
        title="PDEI AI Reasoning Service",
        description=DESCRIPTION,
        version=__version__,
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url=None,
        openapi_url="/openapi.json",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(resolved.cors_origins),
        allow_credentials=False,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["*"],
        expose_headers=["X-PDEI-Provider"],
    )

    app.include_router(health_router)
    app.include_router(v1_router)

    @app.get("/metrics", include_in_schema=False)
    async def metrics() -> Response:
        """Prometheus scrape endpoint (contract 8.6 and section 13)."""
        return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @app.get("/", include_in_schema=False)
    async def root() -> dict[str, Any]:
        return {
            "service": SERVICE_NAME,
            "version": __version__,
            "docs": "/docs",
            "endpoints": [
                "GET /health",
                "GET /ready",
                "POST /v1/investigate",
                "POST /v1/investigate/stream",
                "POST /v1/admission/score",
                "POST /v1/narrative",
                "GET /v1/tools",
                "GET /v1/providers",
                "GET /metrics",
            ],
        }

    @app.exception_handler(Exception)
    async def unhandled(request: Request, exc: Exception) -> JSONResponse:
        """Never leak a stack trace to a caller; always leave one in the logs."""
        log.error(
            "unhandled error",
            path=request.url.path,
            error=str(exc)[:500],
            exc_info=True,
        )
        return JSONResponse(
            status_code=500,
            content={
                "code": "INTERNAL_ERROR",
                "message": "the AI reasoning service failed to handle this request",
                "path": request.url.path,
            },
        )

    instrument_fastapi(app)
    return app


#: Module-level app for ``uvicorn pdei_ai.main:app``.
app = create_app()


def run() -> None:
    """Entry point for ``pdei-ai`` / ``python -m pdei_ai.main``."""
    import uvicorn

    settings = Settings.from_env()
    # The module-level `app`, not the "pdei_ai.main:app" import string: the
    # string makes uvicorn import this module a second time and build a
    # second application (and a second reasoner registry).
    uvicorn.run(
        app,
        host=settings.host,
        port=settings.port,
        log_config=None,  # structlog owns logging; uvicorn's config would undo it
        access_log=True,
    )


if __name__ == "__main__":  # pragma: no cover
    run()
