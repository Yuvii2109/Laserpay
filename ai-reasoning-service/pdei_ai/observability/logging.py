"""Structured JSON logging (platform contract section 13).

Every line goes to stdout as one JSON object and carries ``traceId``,
``spanId``, ``merchantId`` and ``correlationId`` whenever they are known, so
promtail -> Loki can correlate a reasoning step with the Java case it belongs
to.

structlog is configured to render JSON itself and stdlib logging is routed
through the same renderer, so uvicorn access lines and our own lines look the
same in Loki. Both imports are guarded: an observability dependency must never
be the reason the reasoning service will not start.
"""

from __future__ import annotations

import logging
import sys
from collections.abc import MutableMapping
from typing import Any

try:  # pragma: no cover - exercised by the absence path only
    import structlog

    _STRUCTLOG = True
except ImportError:  # pragma: no cover
    structlog = None  # type: ignore[assignment]
    _STRUCTLOG = False

try:  # pragma: no cover
    from opentelemetry import trace as _otel_trace

    _OTEL = True
except ImportError:  # pragma: no cover
    _otel_trace = None  # type: ignore[assignment]
    _OTEL = False

_CONFIGURED = False


def _trace_context(
    _logger: Any, _name: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    """Attach the active trace and span ids, hex-encoded like the OTLP wire format."""
    if not _OTEL:
        return event_dict
    try:
        span = _otel_trace.get_current_span()
        context = span.get_span_context()
        if context is not None and context.is_valid:
            event_dict.setdefault("traceId", format(context.trace_id, "032x"))
            event_dict.setdefault("spanId", format(context.span_id, "016x"))
    except Exception:  # pragma: no cover - never break a log line
        pass
    return event_dict


def _rename_logger(
    _logger: Any, _name: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    """Surface the module name as ``logger`` (see ``get_logger`` for why)."""
    module = event_dict.pop("_logger_name", None)
    if module is not None:
        event_dict.setdefault("logger", module)
    return event_dict


def _service_name(service: str) -> Any:
    """Processor factory that stamps the module name onto every line."""

    def processor(
        _logger: Any, _name: str, event_dict: MutableMapping[str, Any]
    ) -> MutableMapping[str, Any]:
        event_dict.setdefault("service", service)
        return event_dict

    return processor


def configure_logging(level: str = "INFO", service: str = "ai-reasoning-service") -> None:
    """Idempotent logging setup. Safe to call from tests and from lifespan."""
    global _CONFIGURED
    numeric_level = getattr(logging, level.upper(), logging.INFO)

    if _CONFIGURED:
        logging.getLogger().setLevel(numeric_level)
        return

    if _STRUCTLOG:
        structlog.configure(
            processors=[
                structlog.contextvars.merge_contextvars,
                structlog.stdlib.add_log_level,
                # NOTE: no `add_logger_name` here. It reads `logger.name`, which
                # only exists on stdlib loggers, and we render straight to
                # stdout via PrintLogger. `get_logger` binds `logger` instead.
                _rename_logger,
                _service_name(service),
                _trace_context,
                structlog.processors.TimeStamper(fmt="iso", utc=True, key="at"),
                structlog.processors.StackInfoRenderer(),
                structlog.processors.format_exc_info,
                structlog.processors.JSONRenderer(),
            ],
            wrapper_class=structlog.make_filtering_bound_logger(numeric_level),
            logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
            cache_logger_on_first_use=True,
        )

    _configure_stdlib(numeric_level, service)
    _CONFIGURED = True


def _configure_stdlib(numeric_level: int, service: str) -> None:
    """Route uvicorn / httpx / asyncio logs through a JSON formatter too."""
    handler = logging.StreamHandler(sys.stdout)
    try:
        from pythonjsonlogger import jsonlogger

        handler.setFormatter(
            jsonlogger.JsonFormatter(
                "%(asctime)s %(levelname)s %(name)s %(message)s",
                rename_fields={"asctime": "at", "levelname": "level", "name": "logger"},
                static_fields={"service": service},
            )
        )
    except Exception:  # pragma: no cover - plain text is an acceptable degradation
        handler.setFormatter(
            logging.Formatter('{"at":"%(asctime)s","level":"%(levelname)s",'
                              '"logger":"%(name)s","event":"%(message)s"}')
        )

    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(numeric_level)

    # uvicorn installs its own handlers; make them propagate to ours instead.
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access", "httpx", "httpcore"):
        logger = logging.getLogger(name)
        logger.handlers = []
        logger.propagate = True
    logging.getLogger("uvicorn.access").setLevel(max(numeric_level, logging.INFO))


def get_logger(name: str) -> Any:
    """A structlog bound logger when available, a stdlib logger otherwise.

    The module name travels as the initial value ``_logger_name`` rather than
    through ``.bind()``: binding materialises the lazy proxy against whatever
    configuration exists at import time, and for module-level loggers that is
    structlog's default console renderer rather than our JSON one. The
    ``_rename_logger`` processor turns it into the ``logger`` field, matching
    the stdlib-formatted uvicorn lines. (``add_logger_name`` cannot be used -
    it needs a stdlib logger, and we render straight to stdout; ``logger`` is
    also a reserved keyword of ``structlog.get_logger``.)
    """
    if _STRUCTLOG:
        return structlog.get_logger(name, _logger_name=name)
    return logging.getLogger(name)


def bind_request_context(**values: Any) -> None:
    """Bind ids for the rest of this task, e.g. merchantId and correlationId."""
    if not _STRUCTLOG:
        return
    cleaned = {key: value for key, value in values.items() if value is not None}
    if cleaned:
        structlog.contextvars.bind_contextvars(**cleaned)


def clear_request_context() -> None:
    if _STRUCTLOG:
        structlog.contextvars.clear_contextvars()
