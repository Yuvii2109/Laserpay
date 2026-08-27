"""OpenTelemetry tracing (platform contract section 13).

Traces go OTLP/HTTP to the collector at ``OTEL_EXPORTER_OTLP_ENDPOINT``
(default ``http://otel-collector:4318``) with ``service.name`` set to the module
name, so a case that crosses Java -> Kafka -> this service shows as one trace in
Tempo.

Every import is optional and every failure is swallowed. A missing exporter or
an unreachable collector degrades observability; it must never stop the service
from reasoning.
"""

from __future__ import annotations

import contextlib
from typing import Any

from pdei_ai.observability.logging import get_logger

log = get_logger(__name__)

_TRACER_PROVIDER: Any | None = None
_INSTRUMENTED = False


def configure_tracing(
    service_name: str = "ai-reasoning-service",
    otlp_endpoint: str = "http://otel-collector:4318",
    enabled: bool = True,
    service_version: str = "0.1.0",
) -> Any | None:
    """Install a tracer provider with an OTLP/HTTP span exporter.

    Returns the provider, or ``None`` when tracing is disabled or the SDK is
    unavailable.
    """
    global _TRACER_PROVIDER
    if not enabled:
        log.info("tracing disabled by configuration")
        return None
    if _TRACER_PROVIDER is not None:
        return _TRACER_PROVIDER

    try:
        from opentelemetry import trace
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
    except ImportError:
        log.warning("opentelemetry sdk not installed; tracing disabled")
        return None

    resource = Resource.create(
        {
            "service.name": service_name,
            "service.version": service_version,
            "service.namespace": "pdei",
        }
    )
    provider = TracerProvider(resource=resource)

    try:
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

        endpoint = otlp_endpoint.rstrip("/")
        if not endpoint.endswith("/v1/traces"):
            endpoint = f"{endpoint}/v1/traces"
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
        log.info("tracing configured", endpoint=endpoint, service=service_name)
    except Exception as exc:  # pragma: no cover - exporter is environment dependent
        log.warning("otlp span exporter unavailable; spans stay local", error=str(exc))

    trace.set_tracer_provider(provider)
    _TRACER_PROVIDER = provider
    return provider


def instrument_fastapi(app: Any) -> None:
    """Attach the FastAPI instrumentation once, if it is installed."""
    global _INSTRUMENTED
    if _INSTRUMENTED:
        return
    try:
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

        FastAPIInstrumentor.instrument_app(app, excluded_urls="health,ready,metrics")
        _INSTRUMENTED = True
    except Exception as exc:  # pragma: no cover
        log.warning("fastapi instrumentation unavailable", error=str(exc))


def get_tracer(name: str = "pdei_ai") -> Any:
    """A real tracer when the SDK is present, otherwise a no-op stand-in."""
    try:
        from opentelemetry import trace

        return trace.get_tracer(name)
    except ImportError:  # pragma: no cover
        return _NoopTracer()


def shutdown_tracing() -> None:
    """Flush pending spans on shutdown so the last case is not lost."""
    global _TRACER_PROVIDER
    if _TRACER_PROVIDER is None:
        return
    with contextlib.suppress(Exception):  # a failed flush must not block shutdown
        _TRACER_PROVIDER.shutdown()
    _TRACER_PROVIDER = None


class _NoopSpan:
    def set_attribute(self, *_args: Any, **_kwargs: Any) -> None:
        return None

    def record_exception(self, *_args: Any, **_kwargs: Any) -> None:
        return None

    def set_status(self, *_args: Any, **_kwargs: Any) -> None:
        return None

    def __enter__(self) -> _NoopSpan:
        return self

    def __exit__(self, *_args: Any) -> None:
        # Returns None, never True: a no-op span must never swallow an exception.
        return None


class _NoopTracer:
    def start_as_current_span(self, *_args: Any, **_kwargs: Any) -> _NoopSpan:
        return _NoopSpan()
