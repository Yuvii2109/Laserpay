# ---------------------------------------------------------------------------
# ai-reasoning-service - PDEI Python FastAPI reasoning boundary (port 8000).
# uv-based build: dependencies resolve in a cached layer, source copies last.
# ---------------------------------------------------------------------------
FROM ghcr.io/astral-sh/uv:python3.11-bookworm-slim AS builder

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

WORKDIR /app

# Dependency layer: only the manifests, so edits to source do not re-resolve.
COPY pyproject.toml ./
COPY uv.lock* ./
RUN uv venv /app/.venv \
 && uv sync --no-install-project --no-dev 2>/dev/null \
 || uv pip install --python /app/.venv/bin/python \
      "fastapi>=0.115" "uvicorn[standard]>=0.30" "pydantic>=2.7" "httpx>=0.27" \
      "google-genai>=0.3" "tenacity>=8.3" "redis>=5.0" "prometheus-client>=0.20" \
      "opentelemetry-sdk>=1.25" "opentelemetry-exporter-otlp-proto-http>=1.25" \
      "opentelemetry-instrumentation-fastapi>=0.46b0" "structlog>=24.1" \
      "python-json-logger>=2.0" "jsonschema>=4.22"

COPY pdei_ai ./pdei_ai
RUN uv pip install --python /app/.venv/bin/python --no-deps .

# ---------------------------------------------------------------------------
FROM python:3.11-slim-bookworm AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/app/.venv/bin:$PATH" \
    OTEL_SERVICE_NAME=ai-reasoning-service \
    PDEI_AI_PROVIDER=mock \
    PDEI_AI_HOST=0.0.0.0 \
    PDEI_AI_PORT=8000

RUN groupadd --system pdei && useradd --system --gid pdei --home /app pdei
WORKDIR /app

COPY --from=builder /app/.venv /app/.venv
COPY --chown=pdei:pdei pdei_ai ./pdei_ai

USER pdei
EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=5 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2).status == 200 else 1)"

CMD ["python", "-m", "uvicorn", "pdei_ai.main:app", "--host", "0.0.0.0", "--port", "8000"]
