"""Metrics, tracing and structured logging for the AI reasoning service.

Metric names come from platform contract section 13 and are normative. Nothing
in this package may raise into a reasoning path: an observability failure is
allowed to lose a data point, never a case.
"""

from pdei_ai.observability.logging import (
    bind_request_context,
    clear_request_context,
    configure_logging,
    get_logger,
)
from pdei_ai.observability.tracing import (
    configure_tracing,
    get_tracer,
    instrument_fastapi,
    shutdown_tracing,
)

__all__ = [
    "bind_request_context",
    "clear_request_context",
    "configure_logging",
    "configure_tracing",
    "get_logger",
    "get_tracer",
    "instrument_fastapi",
    "shutdown_tracing",
]
