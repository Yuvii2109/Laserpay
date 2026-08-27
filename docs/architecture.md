# PDEI Architecture

Narrative companion to `PLATFORM-CONTRACT.md` (which holds the normative identifiers).
This document explains *why* the system is shaped the way it is. Read the contract for
what things are called; read this for what they are for.

---

## 1. The thesis

> The transaction is short-lived. The evidence required to defend that transaction is a
> long-lived state that must be continuously maintained.

A card payment completes in seconds. The dispute that attacks it may arrive 90 days later.
In that interval the evidence needed to defend the payment — the invoice, the carrier's
delivery scan, the customer's email, the refund policy that was in force *on the day of
sale* — sits in six different systems, each with its own retention window, each free to
change or lose data without telling anyone.

Traditional dispute tooling starts working when the dispute arrives. By then the evidence
state is whatever it happens to be. PDEI inverts this: it treats **dispute-readiness as a
continuously maintained property of every transaction**, monitored from the moment of sale.

Everything else in the architecture is downstream of that one idea.

---

## 2. The two-speed system

The system has a fast, cheap, deterministic path and a slow, expensive, probabilistic one.
The whole design is about keeping traffic on the first and rationing the second.

```
        ~10^7 events                 deterministic, ~O(1) per event
             │                       Kafka → normalize → project → score
             ▼
        ~10^4 dispute candidates      deterministic gap + contradiction detection
             │
             ▼
        ~10^2 genuinely ambiguous     admission control (priority scoring)
             │
             ▼
        ~10^1 AI investigations       Gemini, schema-constrained
             │
             ▼
        ~10^0 human escalations       safety gate said no
```

The AI layer is deliberately **not** proportional to event volume. It is proportional to
*ambiguity*, which is a far smaller and slower-growing quantity. This is what makes a free
Gemini quota sufficient for a system nominally processing millions of events.

The three deterministic short-circuits in contract §9.4 exist to keep the funnel narrow:
a fully-evidenced case with no contradictions never reaches the model, and neither does a
case with no evidence at all, and neither does one already past its deadline.

---

## 3. The three planes

### 3.1 Truth plane — *what happened*

Kafka, PostgreSQL, MinIO, and the evidence domain model. Everything here is deterministic
and reproducible. Amounts are integers. Timestamps are UTC instants. Documents are content
-addressed by SHA-256. History is append-only.

The truth plane answers questions of fact: *does EV-1092 exist, what is its hash, which
transaction is it attached to, when was it observed, what version superseded it.*

### 3.2 Intelligence plane — *what it means*

FastAPI, Gemini, the tool layer, the investigation context. Everything here is advisory.
It reads a curated context and returns a structured opinion.

The intelligence plane answers questions of interpretation: *given a delivery scan showing
delivery on the 3rd and a customer email on the 5th saying nothing arrived, which is more
credible and what does the merchant's best defence look like.*

### 3.3 Control plane — *what we are allowed to do*

The policy engine, Temporal workflows, the safety gate, authorization and audit. It sits
above both other planes and constrains them.

The control plane answers questions of permission: *is PREPARE_REPRESENTMENT allowed for
this merchant at this confidence with this many contradictions, or does a human decide.*

The boundary that matters most: **the intelligence plane can only ever produce a proposal.
The control plane decides.** A `SafetyDecision` is never produced by a model.

---

## 4. Why each piece of infrastructure exists

The reference doc's rule is that no technology enters without a workload that needs it.
The justification for each:

| Component | The workload that requires it |
|---|---|
| **Kafka** | Evidence arrives from independent systems at unrelated times, late and out of order. We need durable, replayable, partition-ordered ingestion — and replay is a first-class product feature (rebuild all state from the log), not just an ops trick. |
| **PostgreSQL** | Financial truth needs transactions, constraints, and joins. The evidence graph is small enough per transaction that adjacency in SQL beats a graph database (see ADR-0003). |
| **MinIO** | Evidence artifacts are blobs (PDFs, EMLs, images). They must be content-addressed and versioned, and they must not live in the database. |
| **Redis** | Idempotency keys, recompute debouncing, distributed locks, the AI token bucket, hot readiness cache. All ephemeral; none authoritative. |
| **Temporal** | A dispute workflow legitimately runs for weeks: wait 7 days for missing evidence, wait for a human, follow up until closure. Encoding that as cron + database state machine is how correctness dies. Temporal gives durable timers, replay, and crash recovery for free. |
| **FastAPI** | The AI boundary must be a separate process in a separate language so that "the AI cannot touch financial state" is enforced by topology, not by discipline. |
| **Tika/PDFBox** | Evidence is documents; documents need text extraction to be searchable and verifiable. |
| **OTel/Prometheus/Grafana/Loki** | A funnel-shaped system is unfalsifiable without measurement. The AI-reduction claim is only credible if it is instrumented. |

Deliberately absent: Neo4j (ADR-0003), Elasticsearch (ADR-0004), Kubernetes locally,
any local LLM.

---

## 5. Event flow, end to end

```
external system / simulator
        │  POST /ingest/v1/events
        ▼
┌─────────────────────┐
│ ingestion-service   │  JSON Schema validation, HMAC webhook verify,
│                     │  Redis SETNX dedupe, idempotency key
└──────────┬──────────┘
           │ pdei.raw.events.v1        key = merchantId:aggregateId
           ▼
┌─────────────────────┐
│ normalization-worker│  SourceAdapter per system → CanonicalEvent
│                     │  schema upcasting; unmappable → DLQ
└──────────┬──────────┘
           │ pdei.canonical.events.v1
           ▼
┌─────────────────────┐
│ state-builder-worker│  per-aggregate handlers, out-of-order guard
│                     │  (ignore events older than last applied),
│                     │  derives evidence from lifecycle facts
└─────┬───────────┬───┘
      │           │ pdei.evidence.events.v1
      │           ▼
      │  ┌─────────────────────┐   ┌────────────────────────┐
      │  │ readiness-worker    │   │ document-processor      │
      │  │ debounce → score    │   │ Tika/PDFBox → FTS text  │
      │  └──────────┬──────────┘   └────────────────────────┘
      │             │ pdei.readiness.events.v1
      │             ▼
      │      api-gateway → WebSocket → Control Tower (live)
      │
      │ pdei.dispute.events.v1
      ▼
┌──────────────────────────┐
│ case-orchestrator        │  DisputeCaseWorkflow (Temporal)
│                          │  gather → gaps → admission → investigate
│                          │  → validate/gate → human? → package → submit
└──────────┬───────────────┘
           │ calls out (HTTP) when admitted
           ▼
┌──────────────────────────┐
│ ai-reasoning-service     │  curated InvestigationContext only
│  Gemini | Mock | Null    │  → schema-constrained InvestigationResult
└──────────┬───────────────┘
           │ result returns to the workflow
           ▼
     AiResultValidator (7 rules) → SafetyGate → ALLOW / ALLOW_WITH_REVIEW / DENY
           │
      ┌────┴─────┐
      ▼          ▼
 auto-prepare   human review queue
      │          │
      └────┬─────┘
           ▼
   representment package (MinIO) + manifest + audit chain entry
```

Every hop writes an audit event. The audit chain is per-merchant and hash-linked, so
tampering with history is detectable by recomputation (`GET /audit/verify-chain`).

---

## 6. The correctness properties we actually claim

These are the properties the design is built to hold, and each is testable:

1. **Idempotency.** Any event delivered N times produces the same state as one delivery.
   Enforced twice: Redis `SETNX` on `pdei:idem:{eventId}` for speed, and
   `processed_events` `INSERT … ON CONFLICT DO NOTHING` for durability.
2. **Out-of-order tolerance.** Projections carry `last_event_occurred_at`; an event older
   than what has been applied is ignored rather than regressing state. `occurredAt` and
   `observedAt` are both retained so lateness is measurable, not invisible.
3. **Replayability.** State is a fold over the canonical event log. Truncating projections
   and replaying from offset 0 must reproduce identical readiness scores. The simulator's
   `POST /sim/v1/replay` exercises this.
4. **Evidence immutability.** A new version never overwrites its parent; the parent moves
   to `SUPERSEDED` and remains retrievable, with `parent_version` linking the chain.
5. **Integrity.** Every artifact carries a SHA-256 recorded at write time. Re-hashing on
   demand detects silent corruption; a mismatch forces `INVALIDATED` and an audit entry.
6. **Determinism of readiness.** The same evidence set and policy version always yields the
   same integer score. No model touches the number.
7. **AI groundedness.** Every factual claim carries an evidence citation; citations are
   resolved against Postgres before the result is accepted; unresolvable citations reject
   the whole result. Counted as `pdei_ai_unsupported_claims_total`.
8. **Workflow durability.** Killing the orchestrator mid-case loses nothing; Temporal
   replays the workflow to its prior point on restart.

Note what is *not* claimed: production throughput numbers. Benchmarks are synthetic and
labelled as such (reference doc §37).

---

## 7. Where the interesting failure modes live

- **The evidence that was never captured.** No amount of engineering recovers a delivery
  scan the carrier never emitted. This is why readiness is monitored continuously and why
  `EXPIRING_SOON` fires at 7 days — the product's value is in the window where a gap is
  still fixable.
- **Policy drift.** The refund policy in force at sale time is not today's policy. Policy
  versions are immutable and cases resolve the version applicable at transaction time.
- **Contradiction, not absence.** Two sources that both exist and disagree (delivery date
  before dispatch date; refund exceeding capture) are more dangerous than a missing
  document, because they can be cited *against* the merchant. `ContradictionDetector`
  exists to find these before an adversary does, and they carry a heavy readiness penalty.
- **The confident wrong model.** Handled structurally: high confidence does not grant
  authority. Rule 7 of the validation gate rejects `DEFENDABLE` while any MANDATORY
  requirement is unsatisfied, no matter what the confidence says.

---

## 8. Deployment shape

Local: one Docker Compose file, three profiles (`core` infrastructure, `app` services,
`obs` observability). Zero cost — Gemini's free tier is the only external dependency, and
`PDEI_AI_PROVIDER=mock` removes even that.

Production-oriented: the same containers on Kubernetes, with worker autoscaling driven by
**Kafka consumer lag** rather than CPU, because the workload is queue-shaped, not
compute-shaped. The API tier and the WebSocket tier scale independently; the AI service
scales on queue depth of admitted investigations, which is intentionally tiny.

---

## 9. Reading order for a new contributor

1. `planner/pre-dispute-evidence-intelligence-reference.md` — the product intent.
2. This file — the shape and the reasoning.
3. `docs/PLATFORM-CONTRACT.md` — the normative identifiers you must not deviate from.
4. `docs/SHARED-LIBRARY-API.md` — the shared types you build on.
5. `backend/evidence-core/context.md` — the domain engine, where the real logic lives.
6. `context.md` at the repo root — the living index of everything.
