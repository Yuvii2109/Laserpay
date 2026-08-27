# ADR-0010 - Reasoner abstraction with mock as the development default

**Status:** Accepted

## Context
Reference doc section 34: the application must not depend directly on Gemini, development must
not burn quota, and tests must be deterministic.

## Decision
`EvidenceReasoner` is a Protocol in `pdei_ai.reasoners.base`, implemented by:

- `GeminiReasoner` - google-genai, JSON response mime type with a schema derived from the
  Pydantic model, tenacity retry with jitter, and one repair re-prompt on invalid JSON;
- `MockReasoner` - fully deterministic, seeded from `investigationId`, no wall clock and no
  unseeded randomness; produces plausible results that cite only evidence present in the
  supplied context;
- `NullReasoner` - always abstains, forcing the deterministic path.

Selected by `PDEI_AI_PROVIDER`. **The development default is `mock`**, with a documented
fallback chain `gemini -> mock`.

## Consequences
- The full stack - including the Case X-Ray's AI Reasoning tab - works with no API key and no
  network. Onboarding and CI need nothing external.
- Tests assert on exact reasoner output because the mock is deterministic.
- Quota exhaustion during a live demo degrades to mock rather than failing, and the UI states
  which provider produced a result, so this is never hidden from the viewer.
- Adding a provider means one new class and one registry entry; nothing in the Java domain
  changes.
