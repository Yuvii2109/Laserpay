"""Primitive value types every PDEI AI model is built from.

Two platform rules are enforced here rather than by convention:

* **Money is integer minor units plus an ISO-4217 currency code.** A float, a
  ``Decimal`` or a string amount is rejected at parse time, not rounded. See
  platform contract section 5 and ``com.laserpay.pdei.common.money.Money``.
* **Time is an ISO-8601 UTC instant.** Naive datetimes are rejected; anything
  with an offset is normalised to UTC. This is the Python mirror of Java
  ``java.time.Instant`` / Postgres ``TIMESTAMPTZ``.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime
from typing import Annotated, Any

from pydantic import (
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    PlainSerializer,
    WithJsonSchema,
    field_validator,
)

# ---------------------------------------------------------------------------
# Identifier patterns (contract section 5: prefixed VARCHAR(64) primary keys)
# ---------------------------------------------------------------------------

EVIDENCE_ID_PATTERN = r"^EV-[A-Za-z0-9._:-]{1,60}$"
INVESTIGATION_ID_PATTERN = r"^INV-[A-Za-z0-9._:-]{1,60}$"
CASE_ID_PATTERN = r"^CASE-[A-Za-z0-9._:-]{1,58}$"
DISPUTE_ID_PATTERN = r"^DSP-[A-Za-z0-9._:-]{1,59}$"
MERCHANT_ID_PATTERN = r"^MER-[A-Za-z0-9._:-]{1,59}$"
TRANSACTION_ID_PATTERN = r"^TX-[A-Za-z0-9._:-]{1,60}$"

_EVIDENCE_ID_RE = re.compile(EVIDENCE_ID_PATTERN)
_CURRENCY_RE = re.compile(r"^[A-Z]{3}$")

EvidenceId = Annotated[
    str,
    Field(
        pattern=EVIDENCE_ID_PATTERN,
        description="Evidence identifier. Always prefixed EV- (platform contract section 5).",
    ),
]
InvestigationId = Annotated[str, Field(pattern=INVESTIGATION_ID_PATTERN)]
Confidence = Annotated[
    float,
    Field(ge=0.0, le=1.0, description="Model confidence in [0,1]. Never a percentage."),
]


def is_evidence_id(value: object) -> bool:
    """True when ``value`` looks like a platform evidence id (``EV-...``)."""
    return isinstance(value, str) and _EVIDENCE_ID_RE.match(value) is not None


# ---------------------------------------------------------------------------
# Instant: ISO-8601 UTC, the Python mirror of java.time.Instant
# ---------------------------------------------------------------------------


def _parse_instant(value: Any) -> Any:
    """Accept ISO-8601 text or an aware datetime; normalise to UTC.

    A naive datetime is refused outright. "Probably UTC" is how timezone bugs
    reach a financial audit trail.
    """
    if isinstance(value, str):
        raw = value.strip()
        if raw.endswith(("Z", "z")):
            raw = raw[:-1] + "+00:00"
        try:
            value = datetime.fromisoformat(raw)
        except ValueError as exc:
            raise ValueError(f"not an ISO-8601 instant: {value!r}") from exc
    if isinstance(value, datetime):
        if value.tzinfo is None:
            raise ValueError(
                "naive datetime rejected: PDEI timestamps are ISO-8601 UTC instants "
                "(platform contract section 5)"
            )
        return value.astimezone(UTC)
    return value


def _format_instant(value: datetime) -> str:
    """Serialise as ``2026-08-26T10:15:30.123Z`` - parseable by Java Instant.parse."""
    utc = value.astimezone(UTC)
    return utc.strftime("%Y-%m-%dT%H:%M:%S.") + f"{utc.microsecond // 1000:03d}Z"


Instant = Annotated[
    datetime,
    BeforeValidator(_parse_instant),
    PlainSerializer(_format_instant, return_type=str, when_used="json"),
    WithJsonSchema(
        {
            "type": "string",
            "format": "date-time",
            "description": "ISO-8601 UTC instant, e.g. 2026-08-26T10:15:30.123Z",
        }
    ),
]


def utc_now() -> datetime:
    """Current instant, always timezone-aware UTC."""
    return datetime.now(UTC)


# ---------------------------------------------------------------------------
# Base model
# ---------------------------------------------------------------------------


class PdeiModel(BaseModel):
    """Base for every model exchanged with Java or the browser.

    ``extra="ignore"`` on purpose: the Java records carry more fields than the
    contract's illustrative JSON (for example the full ``EvidenceView``), and a
    new upstream field must never 422 an investigation.
    """

    model_config = ConfigDict(
        extra="ignore",
        populate_by_name=True,
        validate_assignment=True,
        ser_json_timedelta="iso8601",
        use_enum_values=False,
    )

    def to_wire(self) -> dict[str, Any]:
        """JSON-ready dict: enums as names, instants as ISO-8601 UTC, no ``None``."""
        return self.model_dump(mode="json", exclude_none=True)


# ---------------------------------------------------------------------------
# Money
# ---------------------------------------------------------------------------


class Money(PdeiModel):
    """(amountMinor, currency) - the only monetary representation in PDEI.

    Mirrors ``com.laserpay.pdei.common.money.Money`` and the TypeScript
    ``{ amountMinor: number; currency: string }``. Formatting happens at render
    time only; arithmetic here stays in integer minor units.
    """

    model_config = ConfigDict(
        extra="ignore",
        populate_by_name=True,
        frozen=True,
    )

    amountMinor: int = Field(
        description="Amount in minor units (paise, cents). Integer, never a float."
    )
    currency: str = Field(description="ISO-4217 alphabetic code, uppercase, exactly 3 letters.")

    @field_validator("amountMinor", mode="before")
    @classmethod
    def _reject_non_integer(cls, value: Any) -> Any:
        """Refuse float/Decimal/bool amounts instead of silently rounding them."""
        if isinstance(value, bool):
            raise ValueError("amountMinor must be an integer number of minor units, not a bool")
        if isinstance(value, float):
            raise ValueError(
                f"amountMinor must be an integer number of minor units, got float {value!r} - "
                "floating point money is forbidden (platform contract section 5)"
            )
        if isinstance(value, str):
            text = value.strip()
            if not re.fullmatch(r"-?\d+", text):
                raise ValueError(f"amountMinor must be an integer string, got {value!r}")
            return int(text)
        return value

    @field_validator("currency")
    @classmethod
    def _validate_currency(cls, value: str) -> str:
        code = value.strip().upper()
        if not _CURRENCY_RE.match(code):
            raise ValueError(f"currency must be a 3-letter ISO-4217 code, got {value!r}")
        return code

    @classmethod
    def of(cls, amount_minor: int, currency: str) -> Money:
        return cls(amountMinor=amount_minor, currency=currency)

    @classmethod
    def zero(cls, currency: str) -> Money:
        return cls(amountMinor=0, currency=currency)

    @property
    def is_zero(self) -> bool:
        return self.amountMinor == 0

    @property
    def is_positive(self) -> bool:
        return self.amountMinor > 0

    def plus(self, other: Money) -> Money:
        self._require_same_currency(other)
        return Money(amountMinor=self.amountMinor + other.amountMinor, currency=self.currency)

    def minus(self, other: Money) -> Money:
        self._require_same_currency(other)
        return Money(amountMinor=self.amountMinor - other.amountMinor, currency=self.currency)

    def multiply(self, factor: int) -> Money:
        if isinstance(factor, float):
            raise ValueError("Money.multiply takes an integer factor; floats are forbidden")
        return Money(amountMinor=self.amountMinor * factor, currency=self.currency)

    def to_display_string(self) -> str:
        """Human-readable form, e.g. ``INR 12,999.00``. Display ONLY - never parsed back."""
        sign = "-" if self.amountMinor < 0 else ""
        units, minor = divmod(abs(self.amountMinor), 100)
        return f"{sign}{self.currency} {units:,}.{minor:02d}"

    def _require_same_currency(self, other: Money) -> None:
        if self.currency != other.currency:
            raise ValueError(f"currency mismatch: {self.currency} vs {other.currency}")
