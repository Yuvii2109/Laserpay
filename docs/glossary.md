# Glossary

Domain and platform vocabulary. Where a term has a precise technical meaning in this codebase,
that meaning wins over the industry-general one.

---

## Payments and disputes

**Chargeback** - a forced reversal of a card payment initiated by the cardholder's issuing
bank. In this codebase the entity is called a **Dispute**; chargeback is the colloquial term.

**Dispute** (`DSP-`) - a formal challenge to a transaction, carrying a reason code, a disputed
amount and a **deadline** by which the merchant must respond. Modelled as an entity with a
lifecycle (`DisputeStatus`), not as an event.

**Reason code** (`DisputeReasonCode`) - the category the network assigns to a dispute, such as
`GOODS_NOT_RECEIVED`. It determines which evidence is required, which is why the policy engine
is keyed on it. Network-specific codes (Visa `13.1`, and so on) are carried alongside as
`networkReasonCode` but never drive logic.

**Representment** - the merchant's evidence-backed rebuttal submitted to contest a dispute.
The artifact the platform assembles is the **representment package**: a ZIP of evidence plus a
`manifest.json` listing every file with its hash.

**Deadline** - the point after which a representment can no longer be filed. Drives the
`deadlineUrgency` term in admission scoring and the `EXPIRED` dispute status.

**Liability shift** - who bears the loss. Out of scope: this platform assembles evidence, it
does not adjudicate.

---

## Evidence

**Evidence** (`EV-`) - a versioned, content-addressed artifact or record supporting a
transaction. Has a `type`, a `status`, a `source`, a SHA-256, a version, and provenance
pointing back to the event that created it.

**Evidence readiness** - the deterministic score in [0, 100] answering *"if a dispute arrived
right now, how prepared would we be?"* Computed by `ReadinessEngine`; never model-derived
(ADR-0006).

**Readiness band** - the coarse bucket a score falls into: `READY` (>=90), `NEARLY_READY`
(75-89), `AT_RISK` (50-74), `NOT_READY` (<50). What the UI colours by.

**Evidence gap** - a deficiency detected before a dispute exists. Typed by `GapType`:
`MISSING`, `EXPIRED`, `EXPIRING_SOON`, `CONTRADICTORY`, `UNVERIFIABLE_PROVENANCE`,
`LOW_QUALITY`, `VERSION_CONFLICT`.

**Contradiction** - two pieces of evidence that both exist and disagree (delivery dated before
dispatch; refunds exceeding the capture). More dangerous than a missing document because it
can be cited *against* the merchant, hence the heaviest readiness penalty.

**Provenance** - the record of where an artifact came from: `source`, `sourceEventId`,
`createdAt`, `observedAt`. Evidence whose provenance cannot be established is
`UNVERIFIABLE_PROVENANCE` and carries a -20 penalty.

**Lineage** - the version chain of one artifact, parent to child. Distinct from provenance:
lineage is *this artifact's history*, provenance is *where it entered the system*.

**Superseded** - the status of an evidence version replaced by a newer one. Superseded
evidence stays retrievable forever; nothing is overwritten (ADR-0008).

**Evidence requirement** - what a reason code demands, with a `RequirementStrength` of
`MANDATORY`, `RECOMMENDED`, `OPTIONAL` or `PROHIBITED`. Weights 3 / 2 / 1 / 0 feed the score.

---

## Events and processing

**Canonical event** - the normalized internal event shape every service speaks
(`PLATFORM-CONTRACT.md` section 3). Source-specific payloads exist only upstream of
`normalization-worker`.

**`occurredAt` vs `observedAt`** - when the fact happened in the source system, versus when
PDEI saw it. The gap is **lateness**, and it is deliberately preserved rather than collapsed,
because a late-arriving delivery scan is operationally different from a timely one.

**Aggregate** - the entity an event belongs to (`PAYMENT`, `SHIPMENT`, ...). Combined with
`merchantId` it forms the Kafka partition key, so per-aggregate ordering is guaranteed and
cross-aggregate ordering is not.

**Idempotency** - processing the same event N times yields the same state as once. Enforced by
Redis `SETNX` plus `processed_events` `ON CONFLICT DO NOTHING`.

**Projection** - a read model built by folding events (the `transactions`, `shipments` and
similar tables). Rebuildable by replay; never the primary record of an event.

**Replay** - re-consuming the event log from an offset to rebuild state. A product feature
(proving auditability), not only an operational tool.

**DLQ** - dead-letter queue, `pdei.dlq.v1`. Events that could not be processed land here with
their failure context. Nothing is ever silently dropped.

**Upcasting** - migrating an older `schemaVersion` payload to the current shape at read time.

---

## AI and safety

**Investigation** (`INV-`) - one reasoning pass over one case. Produces an
`InvestigationResult`.

**`InvestigationContext`** - the curated bundle handed to the model. The model sees this and
ten read-only tools; it never sees the database.

**Admission control** - the deterministic gate deciding whether a case is worth a model call
at all (ADR-0009). Cases that fail admission are resolved deterministically or escalated.

**Uncertainty frontier** - the small set of genuinely ambiguous cases left after deterministic
processing. The only population the model ever sees, and the reason a free quota suffices.

**Grounding / citation** - every factual claim in a result must reference an evidence ID
present in the context. Claims that cannot be resolved are **unsupported claims**, and they
invalidate the result.

**Safety gate** - the deterministic layer that accepts, downgrades or rejects a model
recommendation, emitting `ALLOW`, `ALLOW_WITH_REVIEW` or `DENY`. The phrase that summarises the
architecture: *AI proposes, policy disposes.*

**Deterministic short-circuit** - a case resolved without any model call (fully evidenced, or
no evidence at all, or past deadline). Recorded as `aiInvoked: false` with a `bypassReason`,
which is what makes the AI-reduction metric measurable.

**Reasoner** - the provider abstraction (`GeminiReasoner`, `MockReasoner`, `NullReasoner`).
The domain never names a vendor (ADR-0010).

---

## Platform

**Truth / Intelligence / Control plane** - the three architectural layers: what happened, what
it means, what we are allowed to do. See `architecture.md` section 3.

**Evidence graph** - nodes (transaction, payment, order, shipment, delivery, refund,
communication, evidence) and their edges for one transaction. Stored relationally, not in a
graph database (ADR-0003).

**Case** (`CASE-`) - the working container for defending one dispute, driven by a Temporal
`DisputeCaseWorkflow`. Distinct from the dispute itself: one dispute, one case, but the case
holds the process state.

**Case X-Ray** - the UI screen exposing everything about a case: timeline, graph, evidence, AI
reasoning with citations, the safety verdict, and the package. The product's argument that AI
does not decide financial truth.

**Control Tower** - the merchant's landing screen: readiness distribution, at-risk exposure,
expiring evidence, cases needing review.

**Chaos injection** - a deliberate fault triggered from the simulation console to demonstrate
a correctness property. Typed by `ChaosType`.

**Minor units** - the integer representation of money (paise, cents). `amountMinor` plus an
ISO-4217 `currency`. Never a float (ADR-0002).
