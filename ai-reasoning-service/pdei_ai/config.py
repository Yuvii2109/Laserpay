"""Service configuration, read once from the environment.

Shared variable names come from platform contract section 15. Names beginning
``PDEI_AI_`` that are not in section 15 are local to this service and are all
documented in ``context.md`` and ``.env.example``.

Deliberately plain: ``pydantic-settings`` is not a dependency, so this reads
``os.environ`` explicitly. One parse, one immutable object, no hidden lookups
scattered through request handlers.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

DEFAULT_PROVIDER = "mock"
DEFAULT_FALLBACK_CHAIN = ("gemini", "mock")
DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"


def _env(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    return default if value is None or value.strip() == "" else value.strip()


def _env_int(name: str, default: int) -> int:
    raw = _env(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    raw = _env(name)
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = _env(name).lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on"}


def _env_list(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
    raw = _env(name)
    if not raw:
        return default
    return tuple(part.strip() for part in raw.split(",") if part.strip())


@dataclass(frozen=True)
class Settings:
    """Immutable snapshot of the environment for one process lifetime."""

    # --- provider selection (contract 9.5) ---------------------------------
    provider: str = DEFAULT_PROVIDER
    fallback_chain: tuple[str, ...] = DEFAULT_FALLBACK_CHAIN

    # --- Gemini -------------------------------------------------------------
    gemini_api_key: str = ""
    gemini_model: str = DEFAULT_GEMINI_MODEL
    temperature: float = 0.1
    max_output_tokens: int = 4096
    max_attempts: int = 3

    # --- tool callback into api-gateway-service -----------------------------
    api_base_url: str = "http://api-gateway-service:8080"
    service_token: str = "dev-service-token"
    tools_enabled: bool = True
    tool_timeout_seconds: float = 5.0
    tool_max_retries: int = 2
    max_tool_calls: int = 8

    # --- budget / rate limit (contract 9.4 + Redis keys in contract 12) -----
    redis_url: str = "redis://redis:6379"
    daily_budget: int = 1000
    bucket_capacity: int = 10
    bucket_refill_per_second: float = 1.0
    budget_fail_open: bool = True

    # --- admission scoring (contract 9.4) -----------------------------------
    admission_threshold: int = 55
    financial_impact_cap_minor: int = 10_000_000
    ambiguity_cap: int = 8

    # --- http surface -------------------------------------------------------
    host: str = "0.0.0.0"
    port: int = 8000
    cors_origins: tuple[str, ...] = ("http://localhost:3000", "http://localhost:8080")
    require_service_token: bool = False

    # --- observability (contract 13) ----------------------------------------
    otlp_endpoint: str = "http://otel-collector:4318"
    otel_service_name: str = "ai-reasoning-service"
    log_level: str = "INFO"
    tracing_enabled: bool = True

    # --- derived ------------------------------------------------------------
    _warnings: tuple[str, ...] = field(default=(), repr=False)

    @classmethod
    def from_env(cls) -> Settings:
        provider = _env("PDEI_AI_PROVIDER", DEFAULT_PROVIDER).lower()
        gemini_api_key = _env("GEMINI_API_KEY")
        warnings: list[str] = []

        if provider not in {"gemini", "mock", "null"}:
            warnings.append(
                f"PDEI_AI_PROVIDER={provider!r} is not one of gemini|mock|null; "
                f"falling back to {DEFAULT_PROVIDER!r}"
            )
            provider = DEFAULT_PROVIDER
        if provider == "gemini" and not gemini_api_key:
            warnings.append(
                "PDEI_AI_PROVIDER=gemini but GEMINI_API_KEY is empty; the registry will fall "
                "back down the chain (gemini -> mock) so the platform still works"
            )

        return cls(
            provider=provider,
            fallback_chain=_env_list("PDEI_AI_FALLBACK_CHAIN", DEFAULT_FALLBACK_CHAIN),
            gemini_api_key=gemini_api_key,
            gemini_model=_env("GEMINI_MODEL", DEFAULT_GEMINI_MODEL),
            temperature=_env_float("PDEI_AI_TEMPERATURE", 0.1),
            max_output_tokens=_env_int("PDEI_AI_MAX_OUTPUT_TOKENS", 4096),
            max_attempts=max(1, _env_int("PDEI_AI_MAX_ATTEMPTS", 3)),
            api_base_url=_env("PDEI_API_BASE_URL", "http://api-gateway-service:8080").rstrip("/"),
            service_token=_env("PDEI_SERVICE_TOKEN", "dev-service-token"),
            tools_enabled=_env_bool("PDEI_AI_TOOLS_ENABLED", True),
            tool_timeout_seconds=_env_float("PDEI_AI_TOOL_TIMEOUT_SECONDS", 5.0),
            tool_max_retries=max(0, _env_int("PDEI_AI_TOOL_MAX_RETRIES", 2)),
            max_tool_calls=max(0, _env_int("PDEI_AI_MAX_TOOL_CALLS", 8)),
            redis_url=_env("PDEI_REDIS_URL", "redis://redis:6379"),
            daily_budget=_env_int("PDEI_AI_DAILY_BUDGET", 1000),
            bucket_capacity=_env_int("PDEI_AI_BUCKET_CAPACITY", 10),
            bucket_refill_per_second=_env_float("PDEI_AI_BUCKET_REFILL_PER_SECOND", 1.0),
            budget_fail_open=_env_bool("PDEI_AI_BUDGET_FAIL_OPEN", True),
            admission_threshold=_env_int("PDEI_AI_ADMISSION_THRESHOLD", 55),
            financial_impact_cap_minor=_env_int("PDEI_AI_FINANCIAL_IMPACT_CAP_MINOR", 10_000_000),
            ambiguity_cap=max(1, _env_int("PDEI_AI_AMBIGUITY_CAP", 8)),
            host=_env("PDEI_AI_HOST", "0.0.0.0"),
            port=_env_int("PDEI_AI_PORT", 8000),
            cors_origins=_env_list(
                "PDEI_AI_CORS_ORIGINS", ("http://localhost:3000", "http://localhost:8080")
            ),
            require_service_token=_env_bool("PDEI_AI_REQUIRE_SERVICE_TOKEN", False),
            otlp_endpoint=_env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318"),
            otel_service_name=_env("OTEL_SERVICE_NAME", "ai-reasoning-service"),
            log_level=_env("PDEI_AI_LOG_LEVEL", "INFO").upper(),
            tracing_enabled=_env_bool("PDEI_AI_TRACING_ENABLED", True),
            _warnings=tuple(warnings),
        )

    @property
    def startup_warnings(self) -> tuple[str, ...]:
        return self._warnings

    @property
    def tools_base_path(self) -> str:
        """Base path of the read-only tool surface (contract 8.6)."""
        return "/api/v1/ai-tools"

    def redacted(self) -> dict[str, object]:
        """Config as it may appear in logs and on ``GET /v1/providers``."""
        return {
            "provider": self.provider,
            "fallbackChain": list(self.fallback_chain),
            "geminiModel": self.gemini_model,
            "geminiApiKeyPresent": bool(self.gemini_api_key),
            "apiBaseUrl": self.api_base_url,
            "serviceTokenPresent": bool(self.service_token),
            "toolsEnabled": self.tools_enabled,
            "maxToolCalls": self.max_tool_calls,
            "redisConfigured": bool(self.redis_url),
            "dailyBudget": self.daily_budget,
            "bucketCapacity": self.bucket_capacity,
            "admissionThreshold": self.admission_threshold,
            "requireServiceToken": self.require_service_token,
            "tracingEnabled": self.tracing_enabled,
        }
