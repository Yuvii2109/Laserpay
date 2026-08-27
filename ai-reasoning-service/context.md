# `ai-reasoning-service` — module context

> Living document. This is how a future session reloads context on this module.
> Normative sources, in order of authority: `docs/PLATFORM-CONTRACT.md`,
> `docs/SHARED-LIBRARY-API.md`, `schemas/ai/*.schema.json`, then this file.

---

## 1. Purpose

The AI reasoning boundary of PDEI. **This is the only module in the platform
where AI model code may live** (platform contract §17, rules 7 and 14). The Java
side never imports a model SDK; it only calls this service over HTTP through
`com.laserpay.pdei.core.ai.AiReasoningClient`.

The service answers exactly one kind of question: *given this curated view of a
disputed transaction, how defendable is it and why?* It proposes; a
deterministic Java policy gate disposes.

Three properties define it:

| Property | Where it is enforced |
|---|---|
| **Never mutates financial state** | No database access, no Kafka producer, no write tool. The tool layer is GET-only *by construction* (`tools/registry.py`, `tools/executor.py`). |
| **Never invents evidence** | `InvestigationService.self_check` drops any claim citing an evidence id absent from the supplied context, before the result leaves the process. `NarrativeService` additionally redacts unsupported `EV-` tokens from generated prose. |
| **Works with no API key** | `MockReasoner` is the default provider and is fully deterministic. The whole stack runs locally at zero cost. |

---

## 2. Responsibilities

1. Accept an `InvestigationContext` (contract §9.1) and return a
   schema-constrained `InvestigationResult` (contract §9.2), synchronously or as
   an SSE step stream.
2. Select an AI provider and expose the selection honestly (`GET /v1/providers`).
3. Offer the model a **closed, read-only** set of ten tools that call back into
   `api-gateway-service` (contract §8.6), and refuse anything else.
4. Verify every claim against the supplied context before responding.
5. Draft evidence-backed representment narratives.
6. Mirror the deterministic admission-control formula (contract §9.4) so the
   Python side can score standalone, and enforce a Redis daily budget and token
   bucket.
7. Emit the `pdei_ai_*` metrics of contract §13, OTLP traces, and JSON logs.

**Explicitly not responsible for:** deciding whether a result may be acted on
(Java `AiResultValidator` / `SafetyGate`), deciding whether a case reaches the
model at all (Java `AdmissionController`), readiness scoring, evidence
persistence, or anything that changes money.

---

## 3. File-by-file map

```
ai-reasoning-service/
├── pyproject.toml            deps (uv/hatchling), ruff, mypy, pytest config
├── .python-version           3.11
├── .env.example              every env var this service reads, documented
├── Dockerfile                uv builder + slim runtime, non-root, healthcheck
├── .dockerignore
├── context.md                this file
├── pdei_ai/
│   ├── __init__.py           __version__, SERVICE_NAME
│   ├── main.py               create_app(), lifespan wiring, CORS, /metrics, run()
│   ├── config.py             Settings.from_env() — one immutable env snapshot
│   ├── schemas_export.py     generates schemas/ai/*.json; --check mode for CI
│   ├── models/
│   │   ├── common.py         Money, Instant, PdeiModel, id patterns
│   │   ├── enums.py          contract §6 enums (spelling is normative)
│   │   ├── events.py         CanonicalEvent (contract §3)
│   │   ├── investigation.py  InvestigationContext / InvestigationResult + parts
│   │   ├── admission.py      AdmissionRequest / AdmissionDecision / ShortCircuit
│   │   └── narrative.py      NarrativeRequest / NarrativeResult
│   ├── reasoners/
│   │   ├── base.py           EvidenceReasoner Protocol, errors, ReasonerHealth
│   │   ├── gemini.py         GeminiReasoner — the ONLY file importing an AI SDK
│   │   ├── mock.py           deterministic seeded reasoner (default in dev)
│   │   ├── null.py           always abstains
│   │   └── registry.py       provider selection + fallback chain
│   ├── tools/
│   │   ├── registry.py       the ten read-only tools; GET enforced at construction
│   │   ├── client.py         httpx client, GET-only, X-PDEI-Service-Token
│   │   └── executor.py       dispatch + refusals (unknown name, non-GET, budget)
│   ├── services/
│   │   ├── investigation_service.py  reasoner + self-check + SSE stream
│   │   ├── narrative_service.py      narrate + citation filter + redaction
│   │   ├── admission_service.py      contract §9.4 formula, mirrored from Java
│   │   └── budget.py                 Redis daily budget + atomic token bucket
│   ├── api/
│   │   ├── deps.py           app.state accessors, service-token guard
│   │   └── routes/           health, investigate, admission, narrative, tools, providers
│   ├── prompts/
│   │   ├── system.py         versioned system prompts (the prompt contract)
│   │   └── templates.py      context rendering + repair prompt
│   └── observability/
│       ├── metrics.py        prometheus_client, contract §13 names
│       ├── tracing.py        OTel SDK + OTLP/HTTP, all imports optional
│       └── logging.py        structlog JSON to stdout
└── tests/                    160 tests, fully offline
```

Also owned by this module (generated, committed):

```
schemas/ai/investigation-result.schema.json
schemas/ai/investigation-context.schema.json
schemas/ai/admission-decision.schema.json
```

---

## 4. Inbound contracts

### 4.1 HTTP routes served (contract §8.6 — this list is exhaustive)

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/health` | — | liveness; never touches a dependency |
| GET | `/ready` | — | readiness; 503 only when no reasoner is usable |
| POST | `/v1/investigate` | `InvestigationContext` | `InvestigationResult` |
| POST | `/v1/investigate/stream` | `InvestigationContext` | SSE step stream |
| POST | `/v1/admission/score` | `AdmissionRequest` *or* `InvestigationContext` | `AdmissionDecision` |
| POST | `/v1/narrative` | `NarrativeRequest` *or* `InvestigationContext` | `NarrativeResult` |
| GET | `/v1/tools` | — | tool manifest |
| GET | `/v1/providers` | — | active reasoner, chain, health, budget |
| GET | `/metrics` | — | Prometheus text |

Plus `GET /` (endpoint index) and `/docs` + `/openapi.json`, both
`include_in_schema=False` / documentation only. **Adding any other route is a
contract divergence** — `test_openapi_exposes_exactly_the_contract_surface`
fails if the set changes.

Two endpoints accept either body shape because the Java `HttpAiReasoningClient`
posts a bare `InvestigationContext` to all three POST routes:
`admission_request_from_payload` and `narrative._parse_body` normalise.

### 4.2 SSE frame format (`/v1/investigate/stream`)

```
event: <step>
data: {"step": "...", "message": "...", "at": "<iso8601Z>", "detail": {...}}
```

Steps, in order: `accepted`, `context`, `provider`, `reasoning`, `self_check`,
`result`, `done`. On provider failure: `error` replaces `reasoning` onward (the
route then emits `error`, `result` with a deterministic placeholder, `done`).

### 4.3 Headers read

| Header | Meaning |
|---|---|
| `X-PDEI-Service-Token` | Required on `/v1/*` when `PDEI_AI_REQUIRE_SERVICE_TOKEN=true`. Off by default so local demos need no setup. |
| `X-PDEI-Provider` | Optional per-request provider override (`gemini`/`mock`/`null`), so an operator can A/B a case without a redeploy. |

### 4.4 Upstream consumed — the read-only tool surface

`GET {PDEI_API_BASE_URL}/api/v1/ai-tools/*`, with `X-PDEI-Service-Token`. Ten
routes, exactly as contract §8.6 lists them:

| Tool name (model-facing) | Route |
|---|---|
| `getTransaction` | `/api/v1/ai-tools/transaction/{id}` |
| `getOrder` | `/api/v1/ai-tools/order/{id}` |
| `getShipment` | `/api/v1/ai-tools/shipment/{id}` |
| `getRefund` | `/api/v1/ai-tools/refund/{id}` |
| `getEvidence` | `/api/v1/ai-tools/evidence/{id}` |
| `findRelatedEvidence` | `/api/v1/ai-tools/evidence/related?transactionId=` |
| `findContradictions` | `/api/v1/ai-tools/contradictions?transactionId=` |
| `getApplicablePolicy` | `/api/v1/ai-tools/policy/applicable?merchantId=&reasonCode=` |
| `getEvidenceRequirements` | `/api/v1/ai-tools/requirements?reasonCode=` |
| `getTimeline` | `/api/v1/ai-tools/timeline/{transactionId}` |

`/api/v1/health/ready` on the gateway is also called, by the `/ready` probe only.

### 4.5 Not consumed

**No Kafka, no Postgres, no MinIO, no Temporal.** This service holds no
consumer group, so the platform's idempotency rules (contract §4) do not apply
to it. It is a pure request/response boundary; replaying the same
`InvestigationContext` produces the same answer under `mock` and an equivalent
one under `gemini`.

---

## 5. Outbound contracts

### 5.1 `InvestigationResult` (contract §9.2)

Field-identical to `com.laserpay.pdei.core.model.InvestigationResult`. Two
fields are **always set by this service, never by the model**:

* `investigationId` — echoed from the request context. An id a model produces is
  an id it can produce wrongly.
* `modelMetadata` — `provider`, `model`, token counts, `latencyMs`, `attempt`.
  A provider able to describe its own provenance could describe it falsely.

`provider` values seen in the wild: `gemini`, `mock`, `null`, `deterministic`
(the last for the no-provider-available placeholder).

`missingEvidence` holds evidence **type** names (`DELIVERY_PROOF`), never ids —
evidence that does not exist has no id. Validated as `EvidenceType`.

### 5.2 Guarantees the Java side can rely on

1. Every id in `supportingEvidence` and in `citations[].evidenceId` was present
   in the `InvestigationContext` that was posted.
2. No fabricated `EV-` id appears anywhere in the response body, including
   `reasoningSummary`, `narrative` and the SSE `self_check` detail (counts are
   reported; the offending ids go only to the structured log).
3. `confidence` is in `[0,1]`, and is capped at `0.60` whenever any claim was
   dropped — below any plausible `autoPrepareMinConfidence`.
4. A `DEFENDABLE` classification whose supporting evidence was entirely
   unsupported is downgraded to `INSUFFICIENT_EVIDENCE` + `ESCALATE_TO_HUMAN`.
5. `/v1/investigate` answers `200` even when every provider fails, with a
   deterministic `ESCALATE_TO_HUMAN` placeholder tagged
   `provider="deterministic"`. Rationale: a Temporal activity that receives a
   `5xx` retries, and retrying a provider outage burns the retry budget of a
   case that has a deadline.

None of these replace contract §9.3 validation on the Java side — they reduce
how often it has to reject a whole result.

### 5.3 Metrics (contract §13 names are normative)

```
pdei_ai_requests_total{provider,outcome}     outcome: success|filtered|failure
pdei_ai_admission_total{decision}            ADMITTED | <ShortCircuit name>
pdei_ai_latency_seconds{provider}
pdei_ai_unsupported_claims_total
```

Service-local, same prefix: `pdei_ai_tool_calls_total{tool,outcome}`,
`pdei_ai_tool_latency_seconds{tool}`, `pdei_ai_tool_rejected_total{reason}`,
`pdei_ai_tokens_total{provider,kind}`, `pdei_ai_repair_attempts_total{provider,outcome}`,
`pdei_ai_budget_decisions_total{gate,outcome}`,
`pdei_ai_provider_fallbacks_total{requested,selected}`,
`pdei_ai_active_provider{provider}`.

### 5.4 Traces and logs

OTLP/HTTP to `OTEL_EXPORTER_OTLP_ENDPOINT`, `service.name=ai-reasoning-service`.
Span `pdei.ai.investigate` carries `pdei.investigation_id`, `pdei.merchant_id`,
`pdei.transaction_id`, `pdei.provider`, `pdei.classification`,
`pdei.dropped_claims`.

JSON logs to stdout with `traceId`, `spanId`, `service`, `logger`, and
`investigationId`/`merchantId`/`caseId` bound per request.

### 5.5 Redis keys written (contract §12)

```
pdei:ai:budget:{yyyy-MM-dd}   INCR + EXPIRE 48h   daily call budget
pdei:ai:bucket                HASH{tokens,at}     token bucket, Lua-atomic
```

---

## 6. The reasoner abstraction

```python
class EvidenceReasoner(Protocol):
    name: str      # gemini | mock | null  — appears in metrics and ModelMetadata
    model: str
    async def investigate(self, context: InvestigationContext) -> InvestigationResult: ...
    async def narrate(self, request: NarrativeRequest) -> NarrativeResult: ...
    async def health(self) -> ReasonerHealth: ...
```

Everything above the protocol is provider agnostic. Implementations must not
mutate the context, must not raise for ordinary model failure (raise
`ReasonerError` / `InvalidModelOutput` / `ReasonerUnavailable`), and `health()`
must never raise.

### 6.1 `MockReasoner` — default, deterministic

The seed is `int(sha256(investigationId)[:16], 16)` — SHA-256 rather than
`hash()`, whose per-process salt would break reproducibility across restarts.
**The entire output is a pure function of the context**, including
`modelMetadata.latencyMs` and the token estimates, which are *simulated* exactly
so the object stays byte-identical across runs. The token estimate is computed
from `context.model_dump_json()`, not from the rendered prompt, because the
rendered prompt contains "hours until deadline" and would drift with the clock.

The decision tree mirrors the Java `DeterministicInvestigator` so mock runs and
deterministic fallbacks tell the same story; seeded randomness only moves
confidence *within* the band the tree already chose, never across a
classification boundary.

### 6.2 `GeminiReasoner` — the only file importing an AI SDK

* **Schema-constrained output.** `response_mime_type="application/json"` plus a
  response schema *derived from the Pydantic model* by `build_response_schema()`
  — `$ref`s inlined, `anyOf` collapsed to `nullable`, unsupported keywords
  stripped. Because the schema is generated, it cannot drift from the parser.
* **Tool phase is separate.** Gemini will not accept function declarations and a
  forced response schema in the same call, so tools run first (bounded, no
  schema), then a final constrained call with a text digest of the tool results
  appended. Every tool call still goes through `ToolExecutor`.
* **Retries** use a `tenacity` `wait_exponential_jitter` schedule
  (initial 0.5 s, max 8 s, jitter 0.5), degrading to fixed backoff if tenacity is
  absent.
* **Repair pass**: exactly one. The model is shown its own output and the exact
  validation error. Counted in `pdei_ai_repair_attempts_total`.
* **Token accounting** from `usage_metadata.prompt_token_count` /
  `candidates_token_count`, defensively read.

### 6.3 `NullReasoner` — abstention as a configuration

Returns `AMBIGUOUS` / `confidence 0.0` / `ESCALATE_TO_HUMAN`, no citations. It
reports **healthy**, because falling through to another provider would defeat
the point of selecting it. It fails every auto-prepare gate in §9.3 by
construction, which makes it the control case for the safety story.

### 6.4 Fallback chain

`PDEI_AI_PROVIDER` picks the provider; `PDEI_AI_FALLBACK_CHAIN` (default
`gemini,mock`) is walked once at startup. The requested provider is tried first,
then each chain entry, then `mock` as the terminal fallback.

* `gemini` needs `GEMINI_API_KEY` **and** the `google-genai` package; missing
  either means it cannot be constructed and the chain moves on.
* `mock` is always constructible — the chain therefore always terminates.
* **`null` is never a fallback target.** Abstention is chosen deliberately, never
  drifted into. Selecting `null` explicitly short-circuits the whole chain.

Every fall-through is logged and counted in `pdei_ai_provider_fallbacks_total`,
and `GET /v1/providers` reports `requested` vs `active` vs `unavailable` with
reasons. Silent degradation from `gemini` to `mock` would make every conclusion
drawn from a demo wrong.

---

## 7. The prompt contract

`pdei_ai/prompts/system.py`, `SYSTEM_PROMPT_VERSION = "v1"`. The prompt is part
of the contract, not a tuning knob: **change the text, bump the version.**

Every prompt states three rules:

1. **Evidence ids** — only ids present in the supplied context (or returned by a
   tool for the same transaction) may be cited. Never construct, guess or
   extrapolate one.
2. **No uncited claims** — every factual assertion appears in `citations` paired
   with its evidence id. Assessments of the context itself need no citation but
   must not smuggle in an invented fact.
3. **No authority over financial state** — cannot move money, submit a
   representment, alter evidence, or change any status. `recommendedAction` is a
   recommendation to a deterministic engine that decides independently.

Plus: uncertainty is a valid answer, and `confidence` is a calibrated
probability, not a feeling.

`templates.py` renders the context as labelled text (cheaper than pretty JSON)
and ends with an explicit **`## CITABLE EVIDENCE IDS`** block, so the one rule
that matters is impossible to miss.

The prompt version is logged, not appended to `ModelMetadata.model` — that
record has a fixed field set shared with Java and TypeScript. See §11.

---

## 8. The tool boundary

Three refusals happen in `ToolExecutor.execute`, **in code, before any network
call**:

1. **Unknown tool name** — the registry is a closed set of ten. Never forwarded,
   never fuzzy-matched.
2. **Non-GET method** — refused at dispatch, even though `ToolSpec.__post_init__`
   already rejects a non-GET spec at construction. Two independent checks,
   because this is the rule that keeps the model unable to write.
3. **Malformed or unknown arguments** — identifiers only
   (`^[A-Za-z0-9._:-]{1,128}$`, which also defeats path traversal and query
   injection), required arguments present, nothing extra.

Beyond those: a per-investigation call budget (`PDEI_AI_MAX_TOOL_CALLS`), a hard
path-prefix check in `AiToolsClient.get` (`/api/v1/ai-tools/`), and
`follow_redirects=False` so a `3xx` cannot walk a call out of the prefix.

A refusal is returned to the model as a normal tool result carrying an error,
not raised — the model must be able to learn a tool does not exist and carry on;
crashing would turn a model mistake into a platform outage.

`AiToolsClient` exposes exactly one verb, `get`. There is no `post`, no `put`,
no generic `request`. And there is deliberately **no invoke endpoint** on
`/v1/tools`: that would turn a curated, budgeted, audited tool layer into an
open proxy for the gateway.

---

## 9. Configuration and environment variables

Shared names come from contract §15; `PDEI_AI_*` names not in §15 are local to
this service. All of them are in `.env.example`.

| Variable | Default | Meaning |
|---|---|---|
| `PDEI_AI_PROVIDER` | `mock` | `gemini` \| `mock` \| `null` |
| `PDEI_AI_FALLBACK_CHAIN` | `gemini,mock` | ordered chain walked at startup |
| `GEMINI_API_KEY` | *(empty)* | required for the `gemini` provider |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` | |
| `PDEI_AI_TEMPERATURE` | `0.1` | low: this is analysis, not prose generation |
| `PDEI_AI_MAX_OUTPUT_TOKENS` | `4096` | |
| `PDEI_AI_MAX_ATTEMPTS` | `3` | provider call attempts before failing |
| `PDEI_API_BASE_URL` | `http://api-gateway-service:8080` | tool callback target |
| `PDEI_SERVICE_TOKEN` | `dev-service-token` | sent as `X-PDEI-Service-Token` |
| `PDEI_AI_TOOLS_ENABLED` | `true` | when false the model works from context alone |
| `PDEI_AI_TOOL_TIMEOUT_SECONDS` | `5.0` | |
| `PDEI_AI_TOOL_MAX_RETRIES` | `2` | transient failures only; 4xx never retried |
| `PDEI_AI_MAX_TOOL_CALLS` | `8` | per investigation |
| `PDEI_REDIS_URL` | `redis://redis:6379` | empty disables the budget gate |
| `PDEI_AI_DAILY_BUDGET` | `1000` | `<=0` means unlimited |
| `PDEI_AI_BUCKET_CAPACITY` | `10` | token bucket size |
| `PDEI_AI_BUCKET_REFILL_PER_SECOND` | `1.0` | |
| `PDEI_AI_BUDGET_FAIL_OPEN` | `true` | behaviour when Redis is unreachable |
| `PDEI_AI_ADMISSION_THRESHOLD` | `55` | contract §9.4 |
| `PDEI_AI_FINANCIAL_IMPACT_CAP_MINOR` | `10000000` | INR 100,000.00 |
| `PDEI_AI_AMBIGUITY_CAP` | `8` | contradictions×2 + gaps saturation point |
| `PDEI_AI_HOST` / `PDEI_AI_PORT` | `0.0.0.0` / `8000` | contract §2 |
| `PDEI_AI_CORS_ORIGINS` | `http://localhost:3000,http://localhost:8080` | |
| `PDEI_AI_REQUIRE_SERVICE_TOKEN` | `false` | guard `/v1/*` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4318` | |
| `OTEL_SERVICE_NAME` | `ai-reasoning-service` | |
| `PDEI_AI_LOG_LEVEL` | `INFO` | |
| `PDEI_AI_TRACING_ENABLED` | `true` | |

`Settings` is a frozen dataclass read once via `Settings.from_env()`. An invalid
`PDEI_AI_PROVIDER` falls back to `mock` with a logged warning rather than
failing startup. `Settings.redacted()` is what appears in logs and on
`/v1/providers` — the API key is reported only as `geminiApiKeyPresent`.

---

## 10. Dependencies on other modules

| Module | Relationship |
|---|---|
| `api-gateway-service` | **Required for tools only.** Serves `/api/v1/ai-tools/*`. Unavailable ⇒ the model answers from context alone; `/ready` reports `tools: DEGRADED` but stays `UP`. |
| `evidence-core` | Builds the `InvestigationContext` and consumes the result via `AiReasoningClient` / `HttpAiReasoningClient`. Owns validation (§9.3) and admission (§9.4). Type parity with `core.model.*` is mandatory. |
| `case-orchestrator-service` | Calls `/v1/investigate` from the Temporal `investigate` activity (step 6 of `DisputeCaseWorkflow`). |
| `platform-common` | No code dependency — the Python models mirror `common.money.Money`, `common.event.*` and `common.domain.*` by hand. `schemas/ai/*.json` is the referee. |
| Redis | Optional. Budget and rate limit only. |
| `frontend` | Reads `/v1/providers` and consumes the SSE stream on the Case X-Ray page. |

**Nothing depends on this service to make a decision.** Every caller has a
deterministic path that works when it is down.

---

## 11. Cross-language type parity

`SHARED-LIBRARY-API.md` §4 requires these to stay field-identical:

| Concept | Java | Python | TypeScript |
|---|---|---|---|
| Money | `common.money.Money` | `models.common.Money` | `types/common.ts` |
| Canonical event | `common.event.CanonicalEvent` | `models.events.CanonicalEvent` | `types/events.ts` |
| InvestigationContext | `core.model.InvestigationContext` | `models.investigation.InvestigationContext` | `types/ai.ts` |
| InvestigationResult | `core.model.InvestigationResult` | `models.investigation.InvestigationResult` | `types/ai.ts` |

Consequences that look odd in Python and are deliberate:

* **Model fields are camelCase** (`amountMinor`, `investigationId`). Parity beats
  PEP 8 here; `pep8-naming` is therefore not enabled in ruff.
* **`extra="ignore"` on every model.** The Java records carry more fields than
  the contract's illustrative JSON (the full `EvidenceView`, for instance), and a
  new upstream field must never `422` an investigation. The richer optional
  fields (`provenanceVerified`, `qualityScore`, `source`, …) are declared so they
  survive rather than being silently dropped.
* **Money refuses floats at parse time** rather than rounding — a `float`,
  `Decimal` or non-integer string in `amountMinor` is a `ValidationError`.
* **Naive datetimes are refused.** "Probably UTC" is how timezone bugs reach an
  audit trail. Instants serialise as `2026-08-26T10:15:30.123Z`, which
  `java.time.Instant.parse` accepts.
* **`ContradictionRef.left` / `.right` and `GapRef.evidenceId` are plain
  strings, not `EvidenceId`.** They hold an `EV-` id only when the conflict is
  document-backed. `core.readiness.ContradictionDetector.ref(...)` falls back to
  the raw entity id (`DLV-`, `SHP-`, `ORD-`, `REF-`, `PAY-`) when no evidence
  documents the entity, and three of its rules pass the transaction id (`TX-`)
  directly; `core.readiness.GapDetector` then copies `contradiction.left()` into
  the `CONTRADICTORY` gap's `evidenceId`. Applying the `^EV-` pattern here would
  `422` the whole `/v1/investigate` body for exactly the contradiction-bearing
  cases admission control routes to the model (contract §9.4, ambiguity term).
  The `EV-`-only guarantee still holds where it matters:
  `InvestigationContext.evidence_ids()` filters through `is_evidence_id`, so a
  domain entity id never widens the citable universe.

Regenerate the JSON Schemas after any model change:

```bash
uv run pdei-ai-export-schemas          # write
uv run python -m pdei_ai.schemas_export --check   # CI: fail if stale
```

`tests/test_schemas.py` runs `--check`, so a forgotten regeneration fails the
build instead of becoming a silent cross-language mismatch.

---

## 12. Admission control (contract §9.4)

Mirrored in `services/admission_service.py`, with constants named identically to
`core.ai.AdmissionController`. **If the two ever disagree, Java wins and this
file is the one to fix.**

```
priority = 0.40*financialImpact + 0.25*deadlineUrgency
         + 0.20*ambiguityScore  + 0.15*(1 - deterministicConfidence)
admit if priority >= 55 AND daily budget allows AND token bucket allows
```

* `financialImpact` = `amountMinor / 10_000_000`, clamped to 1.0.
* `deadlineUrgency` = 1.0 at ≤48 h, 0.0 at ≥720 h, linear between; **0.5 when the
  deadline is unknown** — neither urgent nor safe to ignore.
* `ambiguityScore` = `(contradictions*2 + gaps) / 8`, clamped. Contradictions
  count double: a self-contradicting case is harder than an incomplete one.
* Rounding is **half up**, via `Decimal`. Python's `round(54.5) == 54` and Java's
  is 55 — at the 55 threshold that difference decides whether a case costs money.

Deterministic short-circuits, evaluated first, in this order (matching Java):

1. past deadline → `ESCALATE_TO_HUMAN`
2. zero evidence → `ACCEPT_LIABILITY`
3. all mandatory satisfied and zero contradictions → `PREPARE_REPRESENTMENT`

When the token bucket refuses after the daily counter incremented, the daily
allowance is **refunded** — otherwise a rate-limited burst eats the day's budget
without a single model call being made.

---

## 13. How to build and run

### Local (uv)

```bash
cd ai-reasoning-service
uv sync                       # or: uv pip install -e ".[dev]"
cp .env.example .env          # optional; every value has a working default
uv run uvicorn pdei_ai.main:app --reload --port 8000
```

Nothing else is required: with `PDEI_AI_PROVIDER=mock` there is no API key, no
Redis and no gateway involved.

### Smoke test

```bash
curl localhost:8000/health
curl localhost:8000/v1/providers | jq '.active, .chainWalked'
curl localhost:8000/v1/tools     | jq '.count, .readOnly'

curl -X POST localhost:8000/v1/investigate -H 'content-type: application/json' -d '{
  "investigationId": "INV-DEMO01",
  "transactionId": "TX-1",
  "reasonCode": "GOODS_NOT_RECEIVED",
  "disputeAmount": {"amountMinor": 1299900, "currency": "INR"},
  "evidence": [{"evidenceId":"EV-1092","type":"DELIVERY_PROOF","status":"ACTIVE"}],
  "requirements": [{"type":"DELIVERY_PROOF","strength":"MANDATORY","satisfied":true,
                    "satisfyingEvidenceIds":["EV-1092"]}]
}'
```

### Switching providers

```bash
PDEI_AI_PROVIDER=mock                                   # default, deterministic, offline
PDEI_AI_PROVIDER=gemini GEMINI_API_KEY=...              # real model; falls back to mock without a key
PDEI_AI_PROVIDER=null                                   # abstain; every case goes to a human
curl -H 'X-PDEI-Provider: null' ... /v1/investigate     # per-request override
```

Confirm what is actually running with `GET /v1/providers` — never assume.

### Tests, lint, types

```bash
uv run pytest            # 160 tests, no network, no Redis, no API key
uv run ruff check .
uv run ruff format .
uv run mypy pdei_ai
```

### Docker

```bash
docker build -t pdei/ai-reasoning-service ai-reasoning-service
docker run --rm -p 8000:8000 --network pdei-net \
  -e PDEI_AI_PROVIDER=mock pdei/ai-reasoning-service
```

Non-root, healthcheck on `/health`, `uv` dependency layer cached separately from
source.

---

## 14. Test suite map

| File | Covers |
|---|---|
| `test_models.py` | float money rejected, currency normalisation, naive datetimes rejected, ISO-8601 `Z` output, `EV-` prefix, contradiction/gap refs accepting domain entity ids (`DLV-`, `SHP-`, `TX-`), confidence bounds, contract §6 enum spelling transcribed, readiness bands, partition key |
| `test_schemas.py` | committed schemas valid and **not stale**, the literal §9.1/§9.2 examples validate, bad ids and out-of-range confidence rejected |
| `test_reasoners.py` | mock byte-determinism (including latency/tokens), decision tree, mock never cites absent evidence, null abstains and reports healthy, registry fallback, `null` never a fallback target |
| `test_investigation_service.py` | the unsupported-claim filter in every direction: invented citations, invented supporting ids, contradictions referencing unknown evidence, confidence capping, DEFENDABLE downgrade, summary annotation, SSE step sequence |
| `test_narrative_service.py` | unsupported citations dropped, `EV-` tokens redacted from prose, fabricated ids never reach a reviewer |
| `test_tools.py` | exactly ten GET tools at the contract paths, non-GET spec refused at construction, unknown/non-GET refused at dispatch **without any HTTP call**, path-traversal arguments refused, call budget, respx-mocked gateway including 404-no-retry and 503-retry |
| `test_admission_service.py` | half-up rounding, each term's boundaries, the 55 threshold (55 admits, 54 does not), all three short-circuits and their order, budget refund on rate limit, both body shapes |
| `test_gemini_reasoner.py` | schema derived from the model, service-owned fields excluded, no `$ref`/`$defs`, `anyOf` → `nullable`, markdown-fence stripping, token accounting, no-key refusal |
| `test_api.py` | every route, SSE frames, reproducibility, 422 on bad money, service-token guard, provider override, `/metrics` names, **OpenAPI path set == contract §8.6** |

---

## 15. Extension points

* **A new provider**: implement the three `EvidenceReasoner` methods, add a
  branch to `ReasonerRegistry._build`, add the name to `KNOWN_PROVIDERS`.
  Nothing above the protocol changes. Keep AI SDK imports inside that one file.
* **A new tool**: add a `ToolSpec` to `TOOL_SPECS` — the manifest, the Gemini
  function declarations and the executor all derive from it. The assertion
  `len(TOOLS) == 10` must be updated *and* contract §8.6 amended first; the
  contract is the source of truth, not the code.
* **Prompt changes**: edit `prompts/system.py` and bump `SYSTEM_PROMPT_VERSION`.
* **New self-check rules**: `InvestigationService.self_check` is pure and
  synchronous; add a rule there and a case to `test_investigation_service.py`.
* **A different admission formula**: change Java first, mirror here, update the
  boundary tests on both sides.
* **New metrics**: add to `observability/metrics.py` with the `pdei_ai_` prefix
  and a `record_*` helper so call sites never wrap metrics in `try`/`except`.

---

## 16. Known gaps and TODOs

Deliberate deterministic fallbacks (working, documented, not stubs):

1. **`GeminiReasoner` has never run against the live API in this repository.**
   `google-genai` is declared but was not installed in the environment where this
   baseline was written, so the SDK call shape (`client.aio.models.generate_content`,
   `types.GenerateContentConfig`, `usage_metadata`) is coded defensively from the
   documented API and guarded with `getattr`/fallbacks, but is **unverified**.
   Everything around it — schema derivation, JSON parsing, repair prompting,
   token accounting, backoff — is unit tested offline. First live run should be
   treated as an integration test.
2. **Tool-phase function responses are appended as JSON text turns**, not as
   proper `types.Content` parts with `function_response`. Works, but is a
   simplification; revisit when the live SDK path is exercised.
3. **Mock token counts and latency are simulated**, not measured — deliberately,
   to keep the mock byte-deterministic (see §6.1). They are labelled
   `provider=mock` so they cannot be mistaken for real usage.
4. **The budget gate fails open by default** when Redis is unreachable. That is a
   policy choice (`PDEI_AI_BUDGET_FAIL_OPEN`), and the Java `AdmissionController`
   still applies its own gate — but a long Redis outage means uncapped spend.
   Watch `pdei_ai_budget_decisions_total{outcome="fail_open"}`.
5. **The service token is a shared secret, not authentication.** It gates model
   budget and curated context, not money — nothing behind it can change financial
   state. It is off by default so local demos need no setup.
6. **The prompt version is logged, not carried in `ModelMetadata`.** Adding a
   `promptVersion` field would diverge from contract §9.2's fixed field set.
   If prompt-level provenance in the audit trail becomes a requirement, amend the
   contract and all three languages together.
7. **`/v1/admission/score` scoring is advisory.** Java owns the real decision, and
   its `deterministicConfidence` is a real computed value; when the body is an
   `InvestigationContext` this service *derives* that term (1.0 / 0.55 / 0.20)
   rather than receiving it. Post a compact `AdmissionRequest` for an exact score.
8. **No caching of identical investigations.** Two identical contexts cost two
   model calls under `gemini`. A Redis result cache keyed on a canonical hash of
   the context would be a natural next step; it was left out because the platform
   rule is "no technology without a workload that needs it".
9. **`/v1/investigate/stream` streams *steps*, not model tokens.** Token-level
   streaming would require a second Gemini code path and would make the
   self-check impossible to apply before the text reaches the client — the
   filter has to see the whole answer.
10. **No `uv.lock` is committed.** It should be generated (`uv lock`) and
    committed on first real dependency install so builds are reproducible; the
    Dockerfile already prefers it and falls back to a direct install if absent.
11. **No per-merchant rate limiting.** The token bucket is global
    (`pdei:ai:bucket`), so one noisy merchant can consume the shared budget.
    Contract §12 reserves `pdei:ratelimit:{merchantId}:{window}` for that.
