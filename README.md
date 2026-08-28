# PDEI - Pre-Dispute Evidence Intelligence

> **Don't wait for a dispute to start building the defence.**

A payment completes in seconds. The chargeback that attacks it may arrive ninety days later.
In that interval the evidence needed to defend the payment - the invoice, the carrier's
delivery scan, the customer's email, the refund policy that was in force *on the day of
sale* - sits in six unrelated systems, each with its own retention window, each free to
change or lose data without telling anyone.

Traditional dispute tooling starts working when the dispute arrives. By then the evidence
state is whatever it happens to be. **PDEI inverts this**: it treats dispute-readiness as a
continuously maintained property of every transaction, monitored from the moment of sale.

For every eligible transaction the platform ingests lifecycle events, normalizes them into a
canonical event model, builds an immutable evidence graph, continuously scores **Evidence
Readiness**, and flags missing, expiring, contradictory or unverifiable evidence *before* any
dispute exists. When a dispute does arrive, the case is already mostly assembled.

### The architectural principle

> **Deterministic systems establish financial truth. AI reasons only about ambiguity.**

The scoring engine is a closed-form function - same inputs, same integer, every time. A
language model is invoked only when the deterministic path cannot resolve a case, its output
is schema-constrained, and every claim it makes is validated against evidence that actually
exists in Postgres before it is allowed to influence anything. The model proposes; policy
disposes.

### What this is not

Not a chatbot, not a document summarizer, not a fraud detector, not a reconciliation
dashboard, and emphatically not an LLM that decides financial truth or moves money.

---

## The two-speed system

The entire design is about keeping traffic on the cheap deterministic path and rationing the
expensive probabilistic one:

```
        ~10^7 events                  deterministic, ~O(1) per event
             │                        Kafka → normalize → project → score
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
        ~10^0 human escalations       the safety gate said no
```

AI cost scales with **ambiguity**, not with event volume. That is what makes a free Gemini
quota sufficient for a system nominally processing millions of events - and the
`PDEI / AI Usage & Safety` Grafana dashboard is where that claim is measured rather than
asserted.

---

## Architecture

```
                       ┌───────────────────────────────┐
                       │   Merchant UI  (Next.js 3000) │
                       │  Control Tower · Case X-Ray   │
                       │  Evidence · Simulation/Chaos  │
                       └───────────────┬───────────────┘
                                       │ HTTPS + WebSocket + SSE
                       ┌───────────────▼───────────────┐
                       │  api-gateway-service   :8080  │
                       │  REST · WS · SSE · ai-tools   │
                       └───────────────┬───────────────┘
                                       │
   ┌───────────────────────────────────┼──────────────────────────────────┐
   │                                   │                                  │
   ▼                                   ▼                                  ▼
┌────────────────────┐      ┌──────────────────────┐        ┌──────────────────────┐
│ ingestion-service  │      │  Query / read paths  │        │ simulator-service    │
│ :8081  raw intake  │      │  readiness, evidence │        │ :8088  world + chaos │
└─────────┬──────────┘      │  cases, audit, gaps  │        └──────────┬───────────┘
          │                 └──────────────────────┘                   │
          │                                                            │
          └──────────────────────────┐             ┌───────────────────┘
                                     ▼             ▼
                       ┌─────────────────────────────────────────┐
                       │        Kafka (KRaft, 8 topics)          │
                       │  raw → canonical → evidence → readiness │
                       │        dispute · case · audit · dlq     │
                       └────────────┬────────────────────────────┘
                                    │  key = merchantId:aggregateId
        ┌───────────────────┬───────┴────────┬───────────────────┬──────────────────┐
        ▼                   ▼                ▼                   ▼                  ▼
┌────────────────┐ ┌────────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌─────────────┐
│ normalization  │ │ state-builder  │ │ readiness    │ │ document-        │ │ audit       │
│ -worker  :8082 │ │ -worker  :8083 │ │ -worker :8084│ │ processor :8086  │ │ -service    │
│ source → canon │ │ projections    │ │ score + gaps │ │ Tika/PDFBox      │ │ :8087 chain │
└───────┬────────┘ └───────┬────────┘ └──────┬───────┘ └────────┬─────────┘ └──────┬──────┘
        │                  │                 │                  │                  │
        └──────────────────┴────────┬────────┴──────────────────┴──────────────────┘
                                    ▼
                       ┌─────────────────────────────┐
                       │  evidence-core  (library)   │
                       │  ReadinessEngine · Policy   │
                       │  GapDetector · SafetyGate   │
                       │  EvidenceGraph · Lineage    │
                       └────┬───────────┬────────┬───┘
                            │           │        │
                  ┌─────────▼──┐  ┌─────▼───┐  ┌─▼──────────┐
                  │ PostgreSQL │  │  Redis  │  │   MinIO    │
                  │  :5432     │  │  :6379  │  │ :9000/9001 │
                  │ operational│  │hot state│  │ evidence + │
                  │   truth    │  │ + dedupe│  │  packages  │
                  └────────────┘  └─────────┘  └────────────┘
                            │
                     dispute arrives
                            ▼
                ┌───────────────────────────┐        ┌───────────────────┐
                │ case-orchestrator :8085   │◄──────►│ Temporal   :7233  │
                │ DisputeCaseWorkflow       │        │ durable timers,   │
                │ gather → gaps → admission │        │ signals, replay   │
                └────────────┬──────────────┘        └───────────────────┘
                             │
                        ambiguity?
                    ┌────────┴─────────┐
                   no                 yes
                    │                  │
                    ▼                  ▼
          ┌──────────────────┐  ┌─────────────────────────┐
          │  Deterministic   │  │ ai-reasoning-service    │
          │  short-circuit   │  │ :8000  FastAPI (Python) │
          │  (zero tokens)   │  │   └─► Gemini            │
          └────────┬─────────┘  └───────────┬─────────────┘
                   │                        │ structured InvestigationResult
                   │                        ▼
                   │            ┌───────────────────────────┐
                   │            │ AiResultValidator +       │
                   │            │ SafetyGate (7 rules)      │
                   │            └───────────┬───────────────┘
                   │                        │
                   └───────────┬────────────┘
                        ┌──────┴────────┐
                        ▼               ▼
                 auto-prepare     human review
                        │               │
                        └───────┬───────┘
                                ▼
                   Representment package (MinIO)
                                ▼
                     Hash-chained audit log
```

Observability runs alongside all of it: OTLP → **otel-collector** → Tempo (traces),
Prometheus (metrics), Loki (logs), rendered by Grafana.

---

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| **Docker Desktop / Engine** | 24+ with Compose **v2** | the whole stack - this alone is enough to run everything |
| JDK | 21 (Temurin) | building a Spring service on the host |
| Maven | 3.9+ | the `backend/` reactor |
| Node | 20+ | the `frontend/` |
| Python | 3.11+ with [`uv`](https://docs.astral.sh/uv/) | `ai-reasoning-service/` |
| Git Bash / WSL2 | - | the `.sh` scripts on Windows (`.ps1` equivalents ship for up/down/reset/smoke-test) |

Resource envelope: roughly **8 GB RAM** and **15 GB disk** for the full stack. Running
`--profile core --profile app` without observability brings that down considerably.

A Gemini API key is **optional**. The default provider is `mock` - deterministic, seeded, and
labelled as such everywhere in the UI. Nothing about the demo requires network access to an
LLM.

---

## Quickstart

```bash
./scripts/bootstrap.sh     # check the toolchain, create infra/.env
./scripts/up.sh            # start core + app + obs, wait for health
./scripts/seed-demo.sh     # generate the demo world from seed 4281
```

On Windows PowerShell:

```powershell
.\scripts\up.ps1
.\scripts\smoke-test.ps1
bash scripts/seed-demo.sh   # Git Bash, or use the simulator UI at /simulation
```

Then open **http://localhost:3000/control-tower**.

### Profiles

The compose file is split into three profiles so you only pay for what you are working on:

```bash
docker compose --profile core up -d                              # infrastructure only
docker compose --profile core --profile app up -d                # + the 11 services
docker compose --profile core --profile app --profile obs up -d  # + observability
```

`./scripts/up.sh core`, `./scripts/up.sh core app` and `./scripts/up.sh` are the same three
in script form.

### Other everyday commands

```bash
./scripts/smoke-test.sh          # health-check every component, print a table
./scripts/logs.sh readiness-worker
./scripts/logs.sh --errors       # every service, ERROR lines only
./scripts/down.sh                # stop; data survives
./scripts/reset.sh               # wipe every volume and start clean
```

---

## Services and ports

Normative source: [`docs/PLATFORM-CONTRACT.md`](docs/PLATFORM-CONTRACT.md) §2. If anything
below disagrees with that table, that table is right.

### Application

| Service | Kind | Host port | Health | Responsibility |
|---|---|---|---|---|
| `api-gateway-service` | Spring Boot (web) | **8080** | `/actuator/health` | REST + WebSocket + SSE; read-only `ai-tools` callbacks |
| `ingestion-service` | Spring Boot (web) | **8081** | `/actuator/health` | raw event intake, schema validation, idempotency |
| `normalization-worker` | Spring Boot (worker) | **8082** | `/actuator/health` | source shapes → `CanonicalEvent` |
| `state-builder-worker` | Spring Boot (worker) | **8083** | `/actuator/health` | projections, evidence graph, dispute detection |
| `readiness-worker` | Spring Boot (worker) | **8084** | `/actuator/health` | readiness score, gaps, contradictions |
| `case-orchestrator-service` | Boot + Temporal worker | **8085** | `/actuator/health` | `DisputeCaseWorkflow`, safety gate, packages |
| `document-processor-service` | Spring Boot (worker+web) | **8086** | `/actuator/health` | Tika/PDFBox extraction, hashing |
| `audit-service` | Spring Boot (worker+web) | **8087** | `/actuator/health` | hash-chained audit log + verification |
| `simulator-service` | Spring Boot (web) | **8088** | `/actuator/health` | synthetic world, chaos injection, replay |
| `ai-reasoning-service` | Python FastAPI | **8000** | `/health` | the only place AI code exists |
| `frontend` (`pdei-web`) | Next.js | **3000** | `/api/health` | Control Tower, Case X-Ray, Chaos console |

### Infrastructure

| Component | Host port | Credentials | Console |
|---|---|---|---|
| PostgreSQL 16 | **5432** | `pdei` / `pdei`, db `pdei` | - |
| Redis 7 | **6379** | none | - |
| Kafka (KRaft, single broker) | **29092** (host) / `kafka:9092` (internal) | none | - |
| Kafka UI | **8090** | none | http://localhost:8090 |
| MinIO API | **9000** | `pdei-minio` / `pdei-minio-secret` | - |
| MinIO Console | **9001** | same | http://localhost:9001 |
| Temporal | **7233** | namespace `pdei` | - |
| Temporal UI | **8233** | none | http://localhost:8233 |
| Prometheus | **9090** | none | http://localhost:9090 |
| Grafana | **3001** | `admin` / `admin` | http://localhost:3001 |
| Loki | **3100** | none | via Grafana |
| Tempo | **3200** | none | via Grafana |
| OTel Collector | **4317** gRPC / **4318** HTTP | none | - |

> Every credential on this page is **dev-only** and hard-coded on purpose. Nothing here is a
> secret, and nothing here is used outside a laptop.

### Grafana dashboards (provisioned automatically)

| Dashboard | Answers |
|---|---|
| `PDEI / Event Pipeline` | throughput, consumer lag, processing latency, duplicates |
| `PDEI / Evidence Readiness` | score distribution, band counts, gap types, expiry funnel |
| `PDEI / AI Usage & Safety` | admission rate, invocations, latency, unsupported claims, budget burn |
| `PDEI / Workflow Health` | workflow starts/completions/failures, case assembly latency |

---

## The demo, in nine beats

Full narration in [`docs/demo-script.md`](docs/demo-script.md). Roughly twelve minutes, fully
reproducible from seed **4281**.

| # | Beat | What you show | What it proves |
|---|---|---|---|
| 0 | Pre-flight | `up.sh`, `smoke-test.sh`, four browser tabs | the stack is real and running |
| 1 | Establish the world | `POST /sim/v1/runs` seed 4281 · Kafka UI filling · Control Tower populating | nothing is seeded; state is a fold over the event log |
| 2 | A ready transaction | readiness breakdown per requirement; `occurredAt` vs `observedAt` | the score is a closed-form function, not an opinion |
| 3 | A gap before any dispute | at-risk feed, a missing `DELIVERY_PROOF`, an `EXPIRING_SOON` item | **this interval is the entire product** |
| 4 | Dispute on a clean transaction | Temporal starts `case-…`; AI tab says *not invoked*, with the bypass reason | zero tokens spent; AI scales with ambiguity |
| 5 | Dispute on an ambiguous one | contradiction flagged; model invoked; every claim rendered beside its evidence ID | the model proposes |
| 6 | The gate refuses the model | rule 7 denies `DEFENDABLE` while a MANDATORY requirement is unsatisfied → human review | policy disposes; no confidence value unlocks this |
| 7 | Chaos | duplicates ×50 (score does not move) · out-of-order · delayed · corrupt hash · kill worker · replay | idempotency, ordering tolerance, integrity, durability, replayability |
| 8 | The funnel | `/observability` and the AI usage dashboard | the AI-reduction claim, measured |
| 9 | Close | - | short-lived transaction, long-lived evidence |

`./scripts/seed-demo.sh` automates beats 1, 5–6 (via the curated scenarios) and part of 7.

Fallbacks, if something breaks on stage:

| If this breaks | Do this |
|---|---|
| Gemini quota exhausted | `PDEI_AI_PROVIDER=mock` - deterministic, and the UI labels it |
| A worker will not start | that *is* `KILL_WORKER`; narrate it as intentional and restart on camera |
| Simulator run too slow | pre-run seed 4281 before the demo; the scenario library is instant |
| Frontend cannot reach the API | `NEXT_PUBLIC_USE_MOCKS=true` renders from fixtures - say so out loud |

---

## Repository layout

```
Laserpay/
├── context.md                  master project context (living document)
├── README.md                   this file
├── planner/                    the original reference document
├── docs/                       contract, architecture, ADRs, runbooks, demo script
├── schemas/                    JSON Schemas: events/, ai/
├── infra/                      docker-compose + every infrastructure config
├── backend/                    Maven reactor (pdei-backend), 12 modules
│   ├── platform-common/        Money, Ids, CanonicalEvent, Topics, Hashes, Json
│   ├── platform-persistence/   JPA entities, repositories, Flyway migrations
│   ├── evidence-core/          the deterministic domain engine
│   └── …nine service modules
├── ai-reasoning-service/       Python FastAPI (uv) - the only AI code in the repo
├── frontend/                   Next.js + TypeScript
├── scripts/                    dev helper scripts (.sh + .ps1)
└── .github/workflows/          CI
```

Every module directory carries its own `context.md` - purpose, responsibilities, file map,
inbound/outbound contracts, config, dependencies, extension points, known gaps. Start there
when you pick a module up.

---

## Where the docs live

| Document | Read it for |
|---|---|
| [`docs/PLATFORM-CONTRACT.md`](docs/PLATFORM-CONTRACT.md) | **NORMATIVE.** Ports, topics, routes, enums, DB schema, env vars. If code disagrees with it, the code is wrong. |
| [`docs/SHARED-LIBRARY-API.md`](docs/SHARED-LIBRARY-API.md) | **NORMATIVE.** The exact shared class and method names every module consumes. |
| [`docs/architecture.md`](docs/architecture.md) | *Why* the system is shaped this way - the three planes, the two-speed funnel |
| [`docs/adr/`](docs/adr/) | The ten decisions that shaped it, with the rejected alternatives |
| [`docs/event-catalog.md`](docs/event-catalog.md) | Every event type, its payload, and who consumes it |
| [`docs/demo-script.md`](docs/demo-script.md) | The twelve-minute walkthrough, beat by beat |
| [`docs/testing-strategy.md`](docs/testing-strategy.md) | What is unit-tested, what needs Testcontainers, what is property-based |
| [`docs/benchmark-plan.md`](docs/benchmark-plan.md) | How performance is measured - and why nothing is claimed without it |
| [`docs/glossary.md`](docs/glossary.md) | Readiness, gap, provenance, representment, admission - the vocabulary |
| [`infra/context.md`](infra/context.md) | Every container, port, volume, credential, startup order, and the local failure modes |
| [`planner/…reference.md`](planner/) | The original product reference the whole repo derives from |

---

## The rules this codebase does not bend

1. The LLM is never the source of truth.
2. The LLM never mutates financial state.
3. Never invent evidence - unsupported claims are rejected.
4. **No floating-point money, ever.** Every amount is `(long amountMinor, String currency)`.
5. No technology without a workload that needs it.
6. Simple correct implementation before distributed complexity.
7. AI provider code isolated behind an abstraction.
8. Provenance and auditability preserved for every artifact.
9. All consumers tolerate duplicates.
10. Assume late and out-of-order events.
11. Reproducible workloads via deterministic seeds.
12. Measure performance; never claim it.
13. The core financial domain stays in Java.
14. AI reasoning stays isolated in Python.
15. The whole stack runs locally at zero cost.

---

## Troubleshooting

Common local failures - port already in use, Kafka refusing to start after a cluster-id
change, Flyway wedged mid-migration, Temporal namespace missing, promtail seeing no logs -
are all documented with fixes in **[`infra/context.md`](infra/context.md)**.

The fastest general-purpose answer:

```bash
./scripts/smoke-test.sh     # find out what is actually down
./scripts/logs.sh <service> # read why
./scripts/reset.sh --yes --up   # when you would rather start over
```
