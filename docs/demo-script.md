# Demo Script

Encodes reference doc §35 (Demo Philosophy) and §36 (Chaos Demonstration) as an executable
sequence. The thesis of the demo: **this is a live event-driven financial platform, not a
dashboard with seeded rows.** Everything on screen must be traceable to an event that was
actually processed while the viewer watched.

Total runtime: ~12 minutes. Every step is reproducible from a fixed seed.

---

## 0. Pre-flight (before the audience is watching)

```bash
./scripts/up.sh              # or scripts/up.ps1 on Windows
./scripts/smoke-test.sh      # every service healthy before you begin
```

Open four browser tabs:

| Tab | URL | Purpose |
|---|---|---|
| Control Tower | http://localhost:3000/control-tower | the merchant's view |
| Grafana | http://localhost:3001 | the operator's view |
| Temporal UI | http://localhost:8233 | workflow durability proof |
| Kafka UI | http://localhost:8090 | the event backbone |

Set `PDEI_AI_PROVIDER=gemini` if quota is available; otherwise `mock` is a legitimate demo
mode and the UI states which provider produced each result. Never hide this.

---

## 1. Establish the world (90s)

**Say:** *"Nothing here is seeded. I'm going to generate a synthetic merchant world and let the
platform build its evidence state from the event stream."*

```bash
curl -X POST localhost:8088/sim/v1/runs -H 'Content-Type: application/json' -d '{
  "seed": 4281, "merchants": 3, "transactions": 2000, "days": 45,
  "disputeRate": 0.02, "failureProfile": "REALISTIC"
}'
```

**Show:** Kafka UI - `pdei.raw.events.v1` filling; then `pdei.canonical.events.v1` behind it.
Then the Control Tower: readiness distribution materialising, the live event ticker moving.

**The point:** events flow in, evidence accumulates, readiness is computed continuously. The
seed makes this identical on every run.

---

## 2. A transaction becoming evidence-ready (90s)

Pick a `READY` transaction. Open `/transactions/{id}` → **Readiness** tab.

**Show:** the score breakdown - each requirement, its strength, whether it is satisfied, and
which penalties applied. Then the **Timeline** tab: `occurredAt` versus `observedAt` on each
row, so lateness is visible rather than smoothed away.

**Say:** *"This 94 isn't a model's opinion. It's a closed-form function of the evidence set and
the policy version in force. Same inputs, same integer, every time."*

---

## 3. A gap detected before any dispute exists (90s)

Go to `/control-tower` → at-risk feed, or `/gaps`. Pick a transaction missing
`DELIVERY_PROOF`.

**Say:** *"No dispute exists here. The platform is telling the merchant that if one arrived
today, they would lose - and there is still time to fix it. That interval is the entire
product."*

**Show:** an `EXPIRING_SOON` item too - evidence with a retention deadline approaching.

---

## 4. Inject a dispute on a well-evidenced transaction (90s)

Simulation console → scenario **`clean-delivery-defendable`**, or:

```bash
curl -X POST localhost:8088/sim/v1/chaos -H 'Content-Type: application/json' \
  -d '{"type":"INJECT_DISPUTE","target":{"transactionId":"TX-…"}}'
```

**Show:** Temporal UI - a `DisputeCaseWorkflow` starts with id `case-…`. Then
`/cases/{caseId}` → the workflow stepper advancing through gather → gaps → admission.

**The critical beat:** on the **AI Reasoning** tab it says the model was **not invoked**, with
the bypass reason: all mandatory requirements satisfied, zero contradictions.

**Say:** *"This case cost zero tokens. It was resolved deterministically. That's the design -
AI scales with ambiguity, not with volume."*

---

## 5. Inject a dispute on an ambiguous transaction (2.5 min)

Scenario **`contradictory-delivery-dates`** - the carrier says delivered on the 3rd, the
customer emailed on the 5th saying nothing arrived.

**Show, in order:**

1. `/cases/{caseId}` → **Evidence**: both artifacts present, both valid, both hashed.
2. → **Overview**: the contradiction is flagged, readiness penalised.
3. → **AI Reasoning**: this time the model *was* invoked. Show the classification, the
   confidence meter, and - most importantly - **every claim rendered next to the evidence ID
   it cites**.
4. → **Safety Gate**: each of the seven validation rules, which passed, which failed.

**Say:** *"The model proposed. Now watch what decides."*

---

## 6. The safety gate refusing the model (2 min)

Scenario **`missing-delivery-proof`** with a model confident enough to want to represent.

**Show:** the Safety Gate tab denying the recommendation - rule 7: `DEFENDABLE` is impossible
while a MANDATORY requirement is unsatisfied. The case routes to `AWAITING_HUMAN_REVIEW`.

**Say:** *"The model was confident and the model was overruled by deterministic policy. There
is no confidence value that unlocks this - the gate isn't a threshold, it's a proof
obligation."*

Then demonstrate the human decision: **Approve** / **Reject** on the case page → Temporal UI
shows the signal landing on the running workflow.

---

## 7. Chaos: prove the correctness properties (3 min)

Run these live from `/simulation`. Each maps to a property from `architecture.md` §6.

| Injection | What to show | Property proven |
|---|---|---|
| `DUPLICATE_EVENT` ×50 | `pdei_events_duplicate_total` rises; readiness score **does not move** | idempotency |
| `OUT_OF_ORDER_EVENT` | handler ignores the stale event; state does not regress | out-of-order tolerance |
| `DELAYED_EVENT` | the row appears with a wide `occurredAt`→`observedAt` gap | lateness is preserved, not hidden |
| `CORRUPT_EVIDENCE_HASH` | evidence flips to `INVALIDATED`, readiness drops, audit entry written | integrity detection |
| `KILL_WORKER` (orchestrator) | Temporal UI: workflow survives; on restart it resumes mid-case | workflow durability |
| `REPLAY_EVENTS` | truncate projections, replay from offset 0, scores land identically | replayability |

**Say on the kill:** *"I just killed the process running that case. Watch the workflow - it
didn't lose its place. That's not retry logic I wrote; that's the workflow being durable."*

**Say on the replay:** *"State is a fold over the log. I can throw the database away and
rebuild it, and the number comes back the same."*

---

## 8. The funnel (60s)

`/observability` → the funnel from `GET /metrics/funnel`:

```
events processed        →  dispute candidates  →  ambiguous  →  AI invoked  →  human escalated
```

**Show:** the measured AI invocation reduction. Then Grafana → `pdei-ai-usage` dashboard:
admission rate, latency, unsupported-claim count, budget burn.

**Say:** *"Every number here was measured by the running system. None of it is asserted."*
(Reference doc §37 - do not claim production performance; label synthetic workloads as such.)

---

## 9. Close (30s)

**Say:** *"The transaction is short-lived. The evidence that defends it is long-lived state
that has to be maintained continuously. Everything you saw follows from that one idea -
deterministic systems establish financial truth, and AI only reasons about what's genuinely
ambiguous."*

---

## Fallback plan

| If this breaks | Do this |
|---|---|
| Gemini quota exhausted | `PDEI_AI_PROVIDER=mock` - deterministic, and the UI labels it |
| A worker will not start | The chaos console's `KILL_WORKER`/restart is the same failure - narrate it as intentional and restart on camera |
| Simulator run too slow | Pre-run seed 4281 before the demo; the scenario library is instant |
| Frontend cannot reach the API | `NEXT_PUBLIC_USE_MOCKS=true` renders every screen from fixtures - state clearly that it is mock data |

## Reproducibility

Every step above is fixed by seed `4281`. Two runs of this script produce the same
transactions, the same readiness scores, and the same AI classifications (with `mock`).
That is what makes it a benchmark rather than an anecdote.
