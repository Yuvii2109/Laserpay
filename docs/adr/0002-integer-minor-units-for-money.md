# ADR-0002 - Integer minor units for all monetary values

**Status:** Accepted

## Context
Floating-point arithmetic cannot represent most decimal fractions exactly. In a system whose
purpose is establishing financial truth for adversarial review, a rounding artifact in a
disputed amount is not a bug - it is a lost case. Reference doc section 21 and rule 39.4
forbid it outright.

## Decision
Every monetary value is a pair: `amountMinor` (`long` / `BIGINT`) and `currency`
(`String` / `CHAR(3)`, ISO-4217). Java type `com.laserpay.pdei.common.money.Money`;
TypeScript `{ amountMinor: number; currency: string }`; Python `Money` (Pydantic).

Arithmetic happens only on the integer. Currency mismatch throws rather than coercing.
Division and percentage operations must state their rounding mode explicitly. Formatting to a
decimal string happens exactly once, at render time, using the currency's own exponent - never
a hardcoded division by 100 (JPY has exponent 0, KWD has 3).

## Consequences
- No `double`, `float`, or `BigDecimal` appears anywhere near an amount, in any of the three
  languages. CI greps for this.
- Comparing amounts across currencies is impossible without an explicit conversion step, which
  is correct: we refuse to silently add INR to USD.
- `amountMinor` as a JSON number is safe to 2^53 minor units, far beyond any realistic
  transaction. If that ever changes it becomes a string.
