# Benchmark Plan

Reference doc section 37 is unambiguous: **do not claim production performance.** Use
synthetic workloads, label them as synthetic, and report only what the implementation actually
measured. This document defines what we measure and how, so that numbers in a presentation can
be traced to a command someone else can re-run.

---

## 1. Ground rules

1. Every benchmark is driven by the simulator with an explicit seed. A number without a seed
   is an anecdote.
2. Every number is produced by the running system's own instrumentation (Prometheus /
   Micrometer / `prometheus_client`), never by a stopwatch in a slide.
3. Report percentiles, not averages, for anything latency-shaped. p50, p95, p99.
4. State the hardware and that it is a single-node Docker Compose environment on a developer
   laptop. This is the opposite of a production claim.
5. If a run cannot be reproduced from its seed, the number is discarded.

---

## 2. The workload generator

```bash
curl -X POST localhost:8088/sim/v1/runs -H 'Content-Type: application/json' -d '{
  "seed": 4281,
  "merchants": 50,
  "transactions": 1000000,
  "days": 90,
  "disputeRate": 0.015,
  "failureProfile": "REALISTIC"
}'
```

`failureProfile` controls the injected pathology mix - late events, duplicates, out-of-order
delivery, missing documents, conflicting sources, partial refunds, multi-shipment orders and
policy changes, per reference doc section 28.

Scale tiers to report, so the shape of the curve is visible rather than one cherry-picked
point:

| Tier | Transactions | Approx. events |
|---|---|---|
| S | 10,000 | ~40,000 |
| M | 100,000 | ~400,000 |
| L | 1,000,000 | ~4,000,000 |
| XL | 10,000,000 | ~40,000,000 (only if the machine sustains it) |

---

## 3. Dimensions to measure

### 3.1 Event pipeline

| Metric | Source | Note |
|---|---|---|
| Events ingested / sec | `pdei_events_ingested_total` rate | sustained, not peak |
| End-to-end event latency | `pdei_event_processing_latency_seconds` | ingest -> projection applied |
| Consumer lag under load | `pdei_kafka_consumer_lag` | the autoscaling signal |
| Duplicate suppression rate | `pdei_events_duplicate_total` / injected duplicates | should be 100% |
| DLQ rate | `pdei.dlq.v1` depth | should be ~0 with a correct adapter set |

### 3.2 Evidence and readiness

| Metric | Source |
|---|---|
| Readiness computation latency p50/p95/p99 | `pdei_readiness_computation_seconds` |
| Evidence completeness | share of transactions reaching band `READY` |
| Gap detection latency | `EvidenceAdded` -> `ReadinessGapDetected` |
| Expiry sweep duration | sweep job timer |

### 3.3 Case assembly

| Metric | Source |
|---|---|
| Case assembly latency p95 | `pdei_case_assembly_seconds` |
| Workflow completion rate | Temporal metrics |
| Workflow recovery time after worker kill | `KILL_WORKER` injection -> resume |

### 3.4 AI layer - the headline claim

| Metric | Source | Why it matters |
|---|---|---|
| **AI invocation reduction** | `1 - (admitted / dispute candidates)` | the funnel thesis (ADR-0009) |
| Admission decisions by outcome | `pdei_ai_admission_total{decision}` | shows the bypasses working |
| AI latency p50/p95 | `pdei_ai_latency_seconds{provider}` | per provider |
| **Unsupported-claim rate** | `pdei_ai_unsupported_claims_total` / results | the groundedness claim |
| Policy gate outcomes | `pdei_policy_gate_total{decision}` | how often the gate overrules |
| Tokens per investigation | `modelMetadata` on results | cost model |

Two of these are the project's actual arguments and must be measured honestly even if the
number is unflattering: the **AI invocation reduction** and the **unsupported-claim rate**.

### 3.5 Correctness under chaos

| Property | Measurement |
|---|---|
| Idempotency | readiness score delta after N duplicate deliveries - must be exactly 0 |
| Out-of-order tolerance | projections that regressed - must be exactly 0 |
| Replay fidelity | share of transactions whose score matches after full replay - target 100% |
| Integrity detection | corrupted artifacts detected / injected - target 100% |
| Workflow durability | cases resumed after worker kill / cases in flight - target 100% |

These are pass/fail properties, not performance numbers. A value below 100% is a bug report,
not a benchmark result.

---

## 4. Reporting template

```
Synthetic benchmark - single-node Docker Compose
Host:        <cpu> / <ram> / <disk>
Seed:        4281
Scale:       1,000,000 transactions / ~4,000,000 events
Provider:    mock  (or: gemini-3.5-flash-lite)
Date:        <iso date>

Sustained ingest rate            XX,XXX events/sec
Event latency p95                XX ms
Consumer lag (steady state)      XX
Duplicate suppression            100.0%
Readiness computation p95        XX ms
Case assembly p95                XX ms
Evidence completeness            XX.X%
AI invocation reduction          XX.X%
Unsupported AI claims            X.XX%
Replay fidelity                  100.0%
Workflow recovery                100.0%

All figures measured by the platform's own instrumentation on synthetic data.
Not a production performance claim.
```

That last line is not boilerplate. It is the difference between an honest engineering result
and a marketing number, and reference doc section 37 asks for the former.

---

## 5. Running a benchmark

```bash
./scripts/reset.sh                     # clean volumes - benchmarks start from empty
./scripts/up.sh
curl -X POST localhost:8088/sim/v1/runs -d @benchmarks/tier-L.json
# watch Grafana -> pdei-event-pipeline until consumer lag stabilises
./scripts/collect-benchmark.sh > benchmarks/results/<date>-tier-L.md
```

Results are committed under `benchmarks/results/` with their seed and host, so a later run can
be compared against an earlier one rather than against memory.
