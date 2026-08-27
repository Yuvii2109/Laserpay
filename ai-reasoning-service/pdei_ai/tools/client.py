"""HTTP client for the read-only tool surface on ``api-gateway-service``.

Direction matters: this service never receives a database connection. When the
model needs a fact that is not in the curated context, the request goes back out
over HTTP to ``/api/v1/ai-tools/*`` with the ``X-PDEI-Service-Token`` header,
where the Java side decides what to answer (platform contract 8.6).

The class exposes exactly one verb - ``get``. There is no ``post``, no ``put``
and no ``request``; the read-only guarantee is a property of the type, not of
reviewer discipline. Two further checks make it hard to break by accident:

* the path must start with ``/api/v1/ai-tools/``;
* redirects are not followed, so a 302 cannot walk the call out of that prefix.
"""

from __future__ import annotations

import time
from typing import Any

import httpx

from pdei_ai.observability.logging import get_logger

log = get_logger(__name__)

SERVICE_TOKEN_HEADER = "X-PDEI-Service-Token"
TOOLS_PATH_PREFIX = "/api/v1/ai-tools/"

# 4xx means the request itself is wrong; repeating it just burns the deadline.
_RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})


class ToolTransportError(RuntimeError):
    """The tool endpoint could not be reached or returned an unusable response."""

    def __init__(self, message: str, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class ToolPathError(ValueError):
    """A path outside the read-only tool prefix was attempted."""


class AiToolsClient:
    """Thin async httpx wrapper, read-only by construction."""

    def __init__(
        self,
        base_url: str,
        service_token: str,
        timeout_seconds: float = 5.0,
        max_retries: int = 2,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.service_token = service_token
        self.timeout_seconds = timeout_seconds
        self.max_retries = max(0, max_retries)
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            base_url=self.base_url,
            timeout=httpx.Timeout(timeout_seconds, connect=min(2.0, timeout_seconds)),
            follow_redirects=False,
            headers={
                SERVICE_TOKEN_HEADER: service_token,
                "Accept": "application/json",
                "User-Agent": "pdei-ai-reasoning-service/0.1",
            },
        )

    async def get(self, path: str, params: dict[str, str] | None = None) -> Any:
        """Issue one GET against the tool surface and return the decoded JSON body.

        Retries only on transient transport failures and retryable status codes,
        with exponential backoff. Everything else raises immediately.
        """
        if not path.startswith(TOOLS_PATH_PREFIX):
            raise ToolPathError(
                f"refusing to call {path!r}: the AI service may only call {TOOLS_PATH_PREFIX}*"
            )

        attempt = 0
        backoff = 0.2
        last_error: Exception | None = None

        while attempt <= self.max_retries:
            attempt += 1
            started = time.perf_counter()
            try:
                response = await self._client.get(path, params=params or None)
            except httpx.HTTPError as exc:
                last_error = ToolTransportError(f"GET {path} failed: {exc}")
                log.warning("tool call transport failure", path=path, attempt=attempt,
                            error=str(exc))
            else:
                elapsed_ms = int((time.perf_counter() - started) * 1000)
                if response.status_code in _RETRYABLE_STATUS:
                    last_error = ToolTransportError(
                        f"GET {path} returned {response.status_code}", response.status_code
                    )
                    log.warning("tool call retryable status", path=path,
                                status=response.status_code, attempt=attempt)
                elif response.status_code == 404:
                    # Not an error worth retrying, and worth telling the model plainly:
                    # "this record does not exist" is itself evidence about the case.
                    raise ToolTransportError(f"GET {path} returned 404", 404)
                elif response.status_code >= 400:
                    raise ToolTransportError(
                        f"GET {path} returned {response.status_code}: {response.text[:200]}",
                        response.status_code,
                    )
                else:
                    log.debug("tool call ok", path=path, status=response.status_code,
                              latencyMs=elapsed_ms)
                    return self._decode(response, path)

            if attempt <= self.max_retries:
                await _sleep(backoff)
                backoff *= 2

        raise last_error or ToolTransportError(f"GET {path} failed")

    @staticmethod
    def _decode(response: httpx.Response, path: str) -> Any:
        if not response.content:
            return None
        try:
            return response.json()
        except ValueError as exc:
            raise ToolTransportError(f"GET {path} returned non-JSON content") from exc

    async def ping(self) -> bool:
        """Readiness probe against the gateway. Never raises."""
        try:
            response = await self._client.get("/api/v1/health/ready")
            return response.status_code < 500
        except httpx.HTTPError:
            return False

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def __aenter__(self) -> AiToolsClient:
        return self

    async def __aexit__(self, *_exc: Any) -> None:
        await self.aclose()


async def _sleep(seconds: float) -> None:
    import asyncio

    await asyncio.sleep(seconds)
