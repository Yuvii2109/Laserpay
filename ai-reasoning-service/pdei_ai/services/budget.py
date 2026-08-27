"""Redis daily budget and token bucket (platform contract 9.4, keys from section 12).

Two independent gates, both in Redis so every replica of every service shares
one view of the spend:

* ``pdei:ai:budget:{yyyy-MM-dd}`` - a counter of model calls made today,
  compared against ``PDEI_AI_DAILY_BUDGET``. Expires 48 hours after creation so
  yesterday's keys clean themselves up.
* ``pdei:ai:bucket`` - a token bucket smoothing bursts: capacity
  ``PDEI_AI_BUCKET_CAPACITY``, refilling at ``PDEI_AI_BUCKET_REFILL_PER_SECOND``.

Both operations are atomic. The token bucket runs as a Lua script because
read-modify-write from several replicas would otherwise let the bucket go
negative under load, which is precisely the situation a rate limiter exists for.

**Behaviour when Redis is unavailable** is a policy choice, not an accident, and
it is configurable via ``PDEI_AI_BUDGET_FAIL_OPEN``:

* fail open (default) - allow the call. A Redis outage degrades cost control;
  refusing every investigation would degrade dispute handling, which is worse.
  The Java ``AdmissionController`` still applies its own gate, so this is not
  the only defence.
* fail closed - refuse. Correct for a deployment where the model spend is the
  binding constraint.

Either way the outcome is counted in ``pdei_ai_budget_decisions_total`` so a
silent switch to unlimited spending is visible on a dashboard.
"""

from __future__ import annotations

import contextlib
import time
from datetime import UTC
from typing import Any

from pdei_ai.models.common import utc_now
from pdei_ai.observability.logging import get_logger
from pdei_ai.observability.metrics import record_budget

log = get_logger(__name__)

BUDGET_KEY_PREFIX = "pdei:ai:budget:"
BUCKET_KEY = "pdei:ai:bucket"
BUDGET_KEY_TTL_SECONDS = 48 * 3600

# Atomic token bucket. KEYS[1]=bucket key, ARGV = capacity, refill rate, now.
# Stores the token count and the last refill timestamp in a hash so the refill
# is computed from elapsed time rather than from a background job.
_BUCKET_LUA = """
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_second = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

local data = redis.call('HMGET', key, 'tokens', 'at')
local tokens = tonumber(data[1])
local last = tonumber(data[2])

if tokens == nil then
  tokens = capacity
  last = now
end

local elapsed = now - last
if elapsed < 0 then elapsed = 0 end
tokens = math.min(capacity, tokens + elapsed * refill_per_second)

local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end

redis.call('HMSET', key, 'tokens', tokens, 'at', now)
redis.call('EXPIRE', key, ttl)
return allowed
"""


def budget_key(now: Any | None = None) -> str:
    """Today's budget key in UTC: ``pdei:ai:budget:2026-08-26``."""
    moment = now or utc_now()
    return BUDGET_KEY_PREFIX + moment.astimezone(UTC).strftime("%Y-%m-%d")


class BudgetGate:
    """Daily call budget plus a token bucket, backed by Redis."""

    def __init__(
        self,
        redis_url: str,
        daily_budget: int = 1000,
        bucket_capacity: int = 10,
        refill_per_second: float = 1.0,
        fail_open: bool = True,
        client: Any | None = None,
    ) -> None:
        self.redis_url = redis_url
        self.daily_budget = daily_budget
        self.bucket_capacity = max(1, bucket_capacity)
        self.refill_per_second = max(0.01, refill_per_second)
        self.fail_open = fail_open
        self._client = client
        self._script_sha: str | None = None
        self._connect_failed = False

    # --- connection ---------------------------------------------------------

    async def _redis(self) -> Any | None:
        if self._client is not None:
            return self._client
        if self._connect_failed:
            return None
        try:
            from redis.asyncio import Redis

            self._client = Redis.from_url(self.redis_url, decode_responses=True)
            return self._client
        except Exception as exc:
            self._connect_failed = True
            log.warning("redis unavailable for AI budget control", error=str(exc)[:200])
            return None

    def _unavailable(self, gate: str) -> bool:
        """Apply the configured fail-open / fail-closed policy."""
        outcome = "fail_open" if self.fail_open else "fail_closed"
        record_budget(gate, outcome)
        return self.fail_open

    # --- daily budget -------------------------------------------------------

    async def try_consume_daily_budget(self, now: Any | None = None) -> bool:
        """Increment today's counter and report whether it stayed within budget."""
        if self.daily_budget <= 0:
            record_budget("daily", "unlimited")
            return True

        client = await self._redis()
        if client is None:
            return self._unavailable("daily")

        key = budget_key(now)
        try:
            used = int(await client.incr(key))
            if used == 1:
                await client.expire(key, BUDGET_KEY_TTL_SECONDS)
        except Exception as exc:
            log.warning("redis budget increment failed", error=str(exc)[:200])
            return self._unavailable("daily")

        if used > self.daily_budget:
            record_budget("daily", "exhausted")
            log.warning("daily AI budget exhausted", used=used, budget=self.daily_budget)
            return False

        record_budget("daily", "allowed")
        return True

    async def refund(self, now: Any | None = None) -> None:
        """Give a daily allowance back when the call did not happen.

        Called when the token bucket refuses after the daily counter already
        incremented. Without it, a rate-limited burst would eat the day's budget
        without a single model call being made.
        """
        if self.daily_budget <= 0:
            return
        client = await self._redis()
        if client is None:
            return
        try:
            await client.decr(budget_key(now))
            record_budget("daily", "refunded")
        except Exception as exc:  # pragma: no cover
            log.warning("redis budget refund failed", error=str(exc)[:200])

    async def remaining_budget(self, now: Any | None = None) -> int | None:
        """Calls left today, or ``None`` when Redis is unreachable."""
        if self.daily_budget <= 0:
            return None
        client = await self._redis()
        if client is None:
            return None
        try:
            raw = await client.get(budget_key(now))
            used = int(raw) if raw is not None else 0
            return max(0, self.daily_budget - used)
        except Exception:  # pragma: no cover
            return None

    # --- token bucket -------------------------------------------------------

    async def try_consume_token(self) -> bool:
        """Take one token, atomically. False when the bucket is empty."""
        client = await self._redis()
        if client is None:
            return self._unavailable("bucket")

        args = [
            str(self.bucket_capacity),
            str(self.refill_per_second),
            str(time.time()),
            str(BUDGET_KEY_TTL_SECONDS),
        ]
        try:
            if self._script_sha is None:
                self._script_sha = await client.script_load(_BUCKET_LUA)
            allowed = await client.evalsha(self._script_sha, 1, BUCKET_KEY, *args)
        except Exception as exc:
            # A flushed script cache or a Redis without EVAL support: fall back
            # to a single EVAL, and only then to the failure policy.
            try:
                allowed = await client.eval(_BUCKET_LUA, 1, BUCKET_KEY, *args)
            except Exception:
                log.warning("redis token bucket failed", error=str(exc)[:200])
                return self._unavailable("bucket")

        ok = bool(int(allowed))
        record_budget("bucket", "allowed" if ok else "limited")
        return ok

    # --- diagnostics --------------------------------------------------------

    async def status(self, now: Any | None = None) -> dict[str, Any]:
        """Snapshot for ``/ready`` and ``GET /v1/providers``."""
        client = await self._redis()
        connected = client is not None
        remaining = await self.remaining_budget(now) if connected else None
        tokens: float | None = None
        if connected:
            try:
                raw = await client.hget(BUCKET_KEY, "tokens")  # type: ignore[union-attr]
                tokens = float(raw) if raw is not None else float(self.bucket_capacity)
            except Exception:  # pragma: no cover
                tokens = None
        return {
            "redisConnected": connected,
            "failOpen": self.fail_open,
            "dailyBudget": self.daily_budget,
            "remainingToday": remaining,
            "bucketCapacity": self.bucket_capacity,
            "refillPerSecond": self.refill_per_second,
            "tokensAvailable": tokens,
            "budgetKey": budget_key(now),
            "bucketKey": BUCKET_KEY,
        }

    async def ping(self) -> bool:
        client = await self._redis()
        if client is None:
            return False
        try:
            return bool(await client.ping())
        except Exception:
            return False

    async def aclose(self) -> None:
        if self._client is None:
            return
        try:
            await self._client.aclose()
        except AttributeError:  # pragma: no cover - older redis-py names it close()
            with contextlib.suppress(Exception):
                await self._client.close()
        except Exception:  # pragma: no cover
            pass
