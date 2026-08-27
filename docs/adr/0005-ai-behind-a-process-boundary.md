# ADR-0005 - AI isolated behind a process and language boundary

**Status:** Accepted

## Context
Rules 39.1, 39.2, 39.13 and 39.14 require that the LLM is never the source of truth, never
mutates financial state, that the financial domain stays in Java, and that AI reasoning stays
in Python. A coding convention would satisfy the letter of this. A boundary satisfies the
intent.

## Decision
All model interaction lives in `ai-reasoning-service`, a separate Python process. The Java
side depends only on `AiReasoningClient`, an HTTP interface. No Gemini SDK, no model client,
and no prompt text exists anywhere under `backend/`.

The AI service holds no database credentials. Its only view of the platform is:

1. the curated `InvestigationContext` it is handed, and
2. ten read-only `GET` endpoints under `/api/v1/ai-tools/*`, token-authenticated.

The tool executor structurally refuses any non-GET request and any tool name outside its
registry - enforced in code, not in the prompt.

## Consequences
- "The AI cannot write financial state" is true by topology. No code path leads from the model
  to a write, so no prompt injection can create one.
- An extra network hop and serialization cost per investigation. Irrelevant: investigations
  are rare by design (ADR-0009) and already take seconds.
- The Java side runs with the AI service entirely absent - it falls back to the deterministic
  path and escalates to a human. Degradation, not outage.
