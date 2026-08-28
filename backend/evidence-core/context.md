# evidence-core - module context

> Maven artifact `evidence-core`, package root `com.laserpay.pdei.core`.
> Implements section 3 of `docs/SHARED-LIBRARY-API.md` and sections 7, 9.3, 9.4 and 11 of
> `docs/PLATFORM-CONTRACT.md`. Those two documents are normative; if this file and they disagree,
> they win.

---

## 1. Purpose

evidence-core is the deterministic domain engine of PDEI. It is a **library**, not a service: it has
no `main`, no web server and no Kafka listeners. Worker and API modules add it as a dependency and
get the whole engine as Spring beans.

Everything in this module is deterministic and auditable. The AI service is treated as an untrusted
advisor behind an interface (`core.ai.AiReasoningClient`), and every answer it gives is re-checked
against database facts before it can influence anything. No Gemini SDK, prompt, or model name exists
anywhere in this module.

The three ideas the module exists to serve:

1. **Evidence readiness is deterministic.** A score is arithmetic over policy requirements and the
   evidence actually attached, never a model judgement.
2. **AI proposes; policy disposes.** The model may suggest; only `core.policy` and `core.safety` can
   permit.
3. **Everything is provable.** Content hashes, a hash-chained audit log, immutable policy versions
   and append-only evidence versions mean any past decision can be reconstructed and re-verified.

---

## 2. Responsibilities

| Area | What this module owns |
|---|---|
| Evidence lifecycle | create, version (never overwrite), expire, invalidate, link, verify integrity |
| Object storage | MinIO layout, sha256 stamping, presigned downloads, bucket bootstrap |
| Readiness | the contract section 7 scoring formula, gap detection, contradiction detection |
| Policy | reason-code to requirement matrix, automation thresholds, expiry rules, immutable versioning |
| Safety | the seven contract section 9.3 rejection rules, plus the escalation gate |
| Admission control | the contract section 9.4 priority formula, three deterministic short-circuits, Redis budget and rate limiting |
| Disputes and cases | dispute lifecycle and transitions, case evidence selection, representment package assembly |
| Read models | timeline, evidence graph, lineage, evidence full-text search, Case X-Ray, funnel metrics |
| Audit | hash-chained append and chain verification |

**Explicitly not owned here:** Kafka consumers (workers), REST controllers (api-gateway-service),
Temporal workflows (case-orchestrator-service), document text extraction
(document-processor-service), any AI prompting (ai-reasoning-service), Flyway migrations
(platform-persistence).

---

## 3. Package and file map

### `core.model` - immutable records shared across services

| File | Notes |
|---|---|
| `EvidenceView` | evidence read model; `isUsable()`, `isExpiredAt()`, `hasVerifiableProvenance()` |
| `RequirementView` | a policy requirement resolved against the evidence actually present |
| `ReadinessGap` | one actionable gap (GapType + GapSeverity + optional evidence id) |
| `ContradictionView` | a cross-record conflict; carries a tolerant Jackson deserializer (object or plain string) |
| `ReadinessSnapshot` | score, band, base, penalties, requirements, gaps, contradictions; `deterministicConfidence()` |
| `EvidenceNode`, `EvidenceEdge`, `EvidenceGraph` | node/edge projection; edge relation names are constants on `EvidenceEdge` |
| `TimelineEntry` | one unified timeline row |
| `InvestigationContext` | contract 9.1, field for field |
| `InvestigationResult` | contract 9.2, field for field; `missingEvidence` is `List<EvidenceType>` (types, not ids - `schemas/ai/investigation-result.schema.json` is the referee); `allReferencedEvidenceIds()` |
| `PolicyConstraints`, `HistoricalContext`, `Citation`, `ModelMetadata` | the nested objects of 9.1 / 9.2 |
| `SafetyVerdict` | `(SafetyDecision, List<String> reasons, List<String> unsupportedClaims)` |
| `CaseXRay` | the whole `/cases/{caseId}/xray` payload |
| `PackageManifest` (+ nested `Item`) | representment bundle manifest written to `pdei-packages` |
| `FunnelMetrics` | events, candidates, ambiguous, aiInvestigated, humanReviewed, autoPrepared, denied |
| `DisputeView`, `CaseView`, `TransactionFacts`, `SearchPage<T>` | supporting read models |

`TransactionFacts` is the flattened projection of a transaction with its payments, orders, order
lines, shipments, deliveries, refunds and communications. It exists so contradiction detection, the
graph builder and the timeline are pure functions over data rather than over a database.

### `core.storage`

| File | Notes |
|---|---|
| `ObjectStore` | put (bytes and stream), get, getBytes, presignedGet, stat, exists, delete, ensureBuckets |
| `MinioObjectStore` | hashes on write, stamps user metadata, ensures buckets on startup (`InitializingBean`), enables versioning on `pdei-evidence` |
| `Buckets` | bucket names, key layout, user-metadata keys, filename sanitisation, content-type inference |
| `StoredObject`, `ObjectStat` | put/stat results; `ObjectStat.recordedSha256()` reads the stamp back |

### `core.evidence`

| File | Notes |
|---|---|
| `EvidenceService` | createEvidence, newVersion, expire, markExpiring, invalidate, link, sweepExpiry, downloadUrl |
| `CreateEvidenceCommand`, `NewVersionCommand` | inputs |
| `EvidenceIntegrityService` | re-hash the stored object, invalidate on mismatch, audit; `metadataMatches()` is the cheap pre-check |
| `IntegrityReport` | verification outcome |
| `EvidenceGraphService` | transaction graph including `CONTRADICTS` edges |
| `EvidenceLineageService` | version chain walk, current head, stored versions, provenance graph |

### `core.readiness`

| File | Notes |
|---|---|
| `ReadinessEngine` | the contract 7 formula; `score(ReadinessInput)` is pure and static |
| `ReadinessInput` | the complete input of that pure function |
| `ReadinessDataProvider` | the injected data port that keeps the engine database-free in tests |
| `DefaultReadinessDataProvider` | production provider over the SPI ports plus `PolicyEngine` |
| `RequirementMatching` | the single definition of "requirement satisfied", shared by engine and detector |
| `GapDetector` | MISSING, EXPIRED, EXPIRING_SOON, UNVERIFIABLE_PROVENANCE, LOW_QUALITY, VERSION_CONFLICT, CONTRADICTORY |
| `ContradictionDetector` | ten cross-record conflict rules |

### `core.policy`

| File | Notes |
|---|---|
| `DefaultPolicyMatrix` | seeded requirements for all ten reason codes, the expiry table, the default thresholds |
| `RequirementSpec` | one requirement: type, strength, weight, maxAgeDays, provenanceRequired, minQualityScore |
| `PolicyView` | an immutable policy version plus thresholds; `toConstraints()` produces the 9.1 object |
| `PolicyEngine` | applicablePolicy, requirements, baselineRequirements, isActionPermitted, evaluateAction, expiryFor, responseDeadline |
| `PolicyVersionService` | immutable publish: append the version, close the previous interval, audit |
| `PolicyDraft`, `PolicyDecision` | publish input and action-evaluation output |

### `core.safety`

| File | Notes |
|---|---|
| `AiResultValidator` | the seven contract 9.3 rules; returns `SafetyVerdict` (ALLOW or DENY) |
| `ValidationInput` | the deterministic ground truth a result is checked against |
| `SafetyGate` | validator + policy thresholds + escalation heuristics, giving ALLOW / ALLOW_WITH_REVIEW / DENY |
| `GateInput` | readiness, dispute value, policy, deadline state |

### `core.ai`

| File | Notes |
|---|---|
| `AiReasoningClient` | the only interface Java uses to reach the model |
| `HttpAiReasoningClient` | RestClient, `X-PDEI-Service-Token`, timeouts, bounded retry, circuit breaker, deterministic fallback |
| `DeterministicInvestigator` | produces a real `InvestigationResult` with no model involved |
| `CircuitBreaker` | closed / open / half-open, hand rolled |
| `AdmissionController` | contract 9.4 priority, short-circuits and throttles; logs every decision |
| `AdmissionRequest`, `AdmissionDecision`, `ShortCircuit`, `AdmissionScore` | admission types |
| `AiBudgetGate`, `RedisAiBudgetGate` | token bucket (Lua) and daily budget on the contract 12 keys |

### `core.dispute`, `core.timeline`, `core.search`, `core.audit`

| File | Notes |
|---|---|
| `DisputeService` | create (idempotent per transaction), validated status transitions, close, expireOverdue |
| `CreateDisputeCommand` | input |
| `CaseAssemblyService` | evidence selection, integrity verification, zip + manifest to `pdei-packages`, `xray()` |
| `TimelineService` | merged entity/evidence/dispute timeline, sorted by when things happened |
| `EvidenceSearchService`, `EvidenceSearchQuery` | Postgres FTS with safe `tsquery` construction |
| `AuditRecorder` | hash-chained append plus Kafka publish; `verifyChain` recomputes and reports the first divergence |
| `AuditCommand`, `ChainVerification` | audit input and verification result |

### `core.spi` - the persistence boundary

Ports: `EvidenceRepositoryPort`, `TransactionRepositoryPort`, `PolicyRepositoryPort`,
`ReadinessRepositoryPort`, `CaseRepositoryPort`, `AuditRepositoryPort`, `EventPublisherPort`.

Records: `EvidenceVersionRecord`, `EvidenceRelationship`, `CaseEvidenceRecord`,
`InvestigationRecord`, `AdmissionLogRecord`.

Adapters: `spi.jdbc.*` (six `NamedParameterJdbcTemplate` adapters plus `JdbcSupport`),
`spi.kafka.KafkaEventPublisher`, `spi.NoOpEventPublisher`.

### `core.util`, `core.config`

`CoreErrors` (the single construction point for the sealed `PdeiException` hierarchy), `Scores`
(round half up and clamp), `Text` (address normalisation, tsquery building), `RedisLocks`.
`CoreProperties` (prefix `pdei.core`), `CoreAutoConfiguration`, `CorePersistenceAutoConfiguration`.

---

## 4. The readiness scoring formula, in prose

`ReadinessEngine.score(ReadinessInput)` is a pure function. Same input, same output, always.

**Step 1 - the base ratio.** Each requirement contributes its weight, discounted by strength:
MANDATORY counts fully, RECOMMENDED counts at half, OPTIONAL and PROHIBITED do not count at all
(they still appear in the snapshot so the UI can show them, but they cannot move the number).
Default weights come from `RequirementStrength.weight()`: MANDATORY 3, RECOMMENDED 2, OPTIONAL 1,
PROHIBITED 0. A merchant policy may override the weight of any individual requirement.

```
numerator   = SUM weight(satisfied MANDATORY) + 0.5 * SUM weight(satisfied RECOMMENDED)
denominator = SUM weight(all MANDATORY)       + 0.5 * SUM weight(all RECOMMENDED)
base        = 100 * numerator / denominator
```

If the denominator is zero - a policy with neither mandatory nor recommended requirements - the base
is **100**, not an error: there is nothing outstanding to be unready about.

**Step 2 - when is a requirement satisfied?** Exactly one definition, in
`RequirementMatching.satisfying`, used by both the engine and the gap detector so they can never
disagree. An artifact satisfies a requirement when all of these hold:

- it is of the required evidence type;
- its status is ACTIVE or EXPIRING (PENDING, EXPIRED, INVALIDATED and SUPERSEDED never satisfy);
- it is not past its `expiresAt` instant, even if the status event has not arrived yet (late events
  must not make an expired document look valid);
- if the requirement sets `provenanceRequired`, the artifact has a source event id, a source and a
  sha256;
- its quality score is at or above the requirement's `minQualityScore`.

A PROHIBITED requirement is never satisfied. Its presence is a policy problem handled by rule 6 of
the safety validator, not a credit towards readiness.

**Step 3 - penalties.** Four rules, applied to the gap list:

| Rule | Cost | Applies to |
|---|---|---|
| CONTRADICTORY gap | −15 each | any contradiction, whichever document carries it |
| EXPIRED evidence | −10 each | only gaps whose evidence type is MANDATORY under this policy |
| EXPIRING_SOON evidence | −5 each | only MANDATORY types; the window is 7 days by default |
| UNVERIFIABLE_PROVENANCE | −20 once | applied a single time no matter how many mandatory artifacts are affected |

MISSING, LOW_QUALITY and VERSION_CONFLICT gaps carry **no** extra penalty: they are already priced
into the base ratio through requirement satisfaction, and charging for them twice would punish the
same fact in two places.

**Step 4 - the final number.**

```
score = clamp(round_half_up(base - penalties), 0, 100)
band  = ReadinessBand.fromScore(score)   // READY >= 90, NEARLY_READY 75-89, AT_RISK 50-74, NOT_READY < 50
```

Rounding is `BigDecimal.HALF_UP` (`Scores.roundHalfUp`). BigDecimal is used here only because the
score is a dimensionless number; **money never touches a decimal or floating type anywhere in this
module** - it is always `(long amountMinor, String currency)`.

**Reason code, or baseline.** `compute(transactionId, reasonCode)` scores against that reason code's
requirements. `compute(transactionId)` scores against the merchant's **baseline requirement
profile**: the union of MANDATORY requirements across the merchant's top reason codes (from their
dispute history; if they have none, the platform default top three - GOODS_NOT_RECEIVED,
FRAUDULENT_TRANSACTION, PRODUCT_NOT_AS_DESCRIBED). Everything in that union stays MANDATORY, because
"mandatory under any reason code this merchant actually receives" is the right bar to hold a
transaction to before a dispute exists.

**Recomputation triggers** (owned by readiness-worker, not by this module): any EVIDENCE event, any
state change on a linked entity, a policy version change, and a nightly sweep for expiry
transitions. Recomputation is safe to run as often as needed because gap ids are deterministic -
`GAP-` plus a hash of transaction, gap type, evidence type and discriminator - so repeated runs
upsert instead of accumulating duplicates.

---

## 5. Gap and contradiction detection

`GapDetector.detect(transactionId, requirements, evidence, contradictions, now)` produces:

| Gap type | Condition | Severity (MANDATORY / RECOMMENDED / other) |
|---|---|---|
| MISSING | no satisfying artifact of the required type | HIGH / MEDIUM / LOW |
| EXPIRED | artifact past `expiresAt`, or status EXPIRED | HIGH / MEDIUM / LOW |
| EXPIRING_SOON | `expiresAt` inside the window (7 days) | MEDIUM / LOW / LOW |
| UNVERIFIABLE_PROVENANCE | no source event id, source or sha256 | CRITICAL / MEDIUM / LOW |
| LOW_QUALITY | quality score below the floor (0.5 by default) | MEDIUM / LOW / LOW |
| VERSION_CONFLICT | more than one live version in the same chain | HIGH / MEDIUM / MEDIUM |
| CONTRADICTORY | one per contradiction | the contradiction's own severity |

When artifacts of a required type exist but none is usable, a MISSING gap is still raised - but only
if nothing more specific already explains why (so an expired document produces EXPIRED, not
EXPIRED *and* MISSING).

`ContradictionDetector.detect(facts, evidence, now)` implements ten rules:

1. delivery timestamp earlier than the shipment dispatch timestamp - HIGH, field `deliveredAt`
2. a delivery exists for a shipment that was never dispatched - HIGH, field `dispatchedAt`
3. delivery timestamp earlier than the order creation timestamp - HIGH, field `deliveredAt`
4. total refunded greater than total captured - CRITICAL, field `refundAmount`
5. a single refund greater than the payment it refunds - CRITICAL, field `refundAmount`
6. refund currency different from the payment currency - CRITICAL, field `currency`
7. delivery address different from the order shipping address - HIGH, field `deliveryAddress`
8. shipment destination different from the order shipping address - MEDIUM, field `deliveryAddress`
9. shipped quantity different from ordered quantity - MEDIUM, field `quantity`
10. captured amount different from the single order total - MEDIUM, field `amountMinor`

Money comparisons are always `long` minor units after a currency check; a currency mismatch is
reported as its own contradiction instead of being compared numerically. Addresses are compared
after `Text.normalizeAddress` (accent stripping, case folding, punctuation removal, whitespace
collapse and a small abbreviation table) so formatting alone never raises a false conflict. **Missing
data is never a contradiction** - an absent address or quantity is a provenance/completeness gap and
belongs to `GapDetector`.

Each contradiction names both sides. When an evidence artifact documents one of the conflicting
records (`EvidenceView.relatedEntityId`), the evidence id is used; otherwise the domain entity id is.

---

## 6. The default policy matrix

`DefaultPolicyMatrix` is the seeded fallback used whenever a merchant has not published a policy of
their own. It is pure data, so a score computed against it is reproducible.

**Requirements per reason code** (M = MANDATORY, R = RECOMMENDED, O = OPTIONAL):

| Reason code | Mandatory | Recommended | Optional |
|---|---|---|---|
| GOODS_NOT_RECEIVED | PAYMENT_PROOF, INVOICE, SHIPPING_RECORD, DELIVERY_PROOF, MERCHANT_POLICY | ORDER_RECORD, CUSTOMER_COMMUNICATION | TERMS_OF_SERVICE, PRIOR_TRANSACTION_HISTORY |
| SERVICE_NOT_RENDERED | PAYMENT_PROOF, INVOICE, SIGNED_CONTRACT, MERCHANT_POLICY | CUSTOMER_COMMUNICATION, ORDER_RECORD, TERMS_OF_SERVICE | PRIOR_TRANSACTION_HISTORY |
| PRODUCT_NOT_AS_DESCRIBED | PAYMENT_PROOF, INVOICE, ORDER_RECORD, DELIVERY_PROOF, MERCHANT_POLICY | CUSTOMER_COMMUNICATION, TERMS_OF_SERVICE, SHIPPING_RECORD | PRIOR_TRANSACTION_HISTORY |
| DUPLICATE_PROCESSING | PAYMENT_PROOF, INVOICE, PRIOR_TRANSACTION_HISTORY | ORDER_RECORD, REFUND_RECEIPT | CUSTOMER_COMMUNICATION |
| CREDIT_NOT_PROCESSED | PAYMENT_PROOF, REFUND_RECEIPT, MERCHANT_POLICY | CUSTOMER_COMMUNICATION, INVOICE | ORDER_RECORD, TERMS_OF_SERVICE |
| SUBSCRIPTION_CANCELLED | PAYMENT_PROOF, TERMS_OF_SERVICE, CUSTOMER_COMMUNICATION, MERCHANT_POLICY | INVOICE, SIGNED_CONTRACT | PRIOR_TRANSACTION_HISTORY |
| FRAUDULENT_TRANSACTION | PAYMENT_PROOF, AVS_CVV_RESULT, DEVICE_FINGERPRINT, DELIVERY_PROOF | PRIOR_TRANSACTION_HISTORY, SHIPPING_RECORD, ORDER_RECORD | CUSTOMER_COMMUNICATION |
| UNRECOGNIZED_TRANSACTION | PAYMENT_PROOF, INVOICE, ORDER_RECORD | PRIOR_TRANSACTION_HISTORY, DEVICE_FINGERPRINT, CUSTOMER_COMMUNICATION, AVS_CVV_RESULT | DELIVERY_PROOF |
| INCORRECT_AMOUNT | PAYMENT_PROOF, INVOICE, ORDER_RECORD | MERCHANT_POLICY, CUSTOMER_COMMUNICATION, REFUND_RECEIPT | TERMS_OF_SERVICE |
| PAID_BY_OTHER_MEANS | PAYMENT_PROOF, INVOICE, PRIOR_TRANSACTION_HISTORY | ORDER_RECORD, CUSTOMER_COMMUNICATION | REFUND_RECEIPT |

**Prohibited evidence types are empty in every default.** Prohibition is a merchant or regional
decision (for example a merchant who must not attach raw device fingerprints to a network
submission), so it belongs in a published merchant policy rather than in a platform assumption.
Safety rule 6 exists to enforce whatever a merchant does declare.

**Expiry rules** (`maxAgeDays`, the age at which an artifact stops satisfying its requirement):

| Days | Evidence types |
|---|---|
| 3650 | PAYMENT_PROOF, INVOICE, ORDER_RECORD, REFUND_RECEIPT, SIGNED_CONTRACT |
| 730 | CUSTOMER_COMMUNICATION, PRIOR_TRANSACTION_HISTORY |
| 540 | SHIPPING_RECORD, DELIVERY_PROOF |
| 365 | MERCHANT_POLICY, TERMS_OF_SERVICE |
| 180 | AVS_CVV_RESULT, DEVICE_FINGERPRINT |

The reasoning: financial records are kept for the statutory retention window, while attestations and
risk signals go stale quickly and must be re-captured to stay credible.

**Default automation thresholds:**

| Setting | Default | Meaning |
|---|---|---|
| `autoPrepareMinConfidence` | 0.90 | below this, PREPARE_REPRESENTMENT is refused (safety rule 4) |
| `maxContradictions` | 0 | one contradiction blocks automated preparation (safety rule 5) |
| `minReadinessScoreForAutoPrepare` | 75 | readiness floor for unattended preparation |
| `humanReviewAboveAmountMinor` | 5 000 000 (INR 50,000.00) | above this value a human always reviews |
| `autoSubmitEnabled` | false | submission is never automatic by default |
| `responseWindowDays` | 21 | representment deadline when the network does not supply one |
| `expiringSoonDays` | 7 | the EXPIRING_SOON window of contract section 7 |
| `permittedActions` | all five | merchants narrow this; nothing widens it at runtime |

Policy resolution order in `PolicyEngine.applicablePolicy`: the merchant version in force for that
reason code, then the merchant version with no reason code (their house policy, with requirements
filled in from the seeded matrix), then the seeded default. Every fallback is deterministic, so the
engine keeps working with an empty policy table.

`PolicyVersionService.publish` never rewrites a stored version. It appends a new one, closes the
previous interval by setting `effective_to`, and audits the transition - so a decision made months
ago can be replayed against exactly the rules that were in force at the time. Each version carries a
`checksum` (canonical-JSON sha256 of the draft) so a no-op republish is visible in the audit trail.

---

## 7. The safety model

### 7.1 `AiResultValidator` - the seven hard rejection rules (contract 9.3)

An `InvestigationResult` is **rejected** when ANY of these hold. Each has a stable reason code that
appears in `SafetyVerdict.reasons`:

1. **`RULE_1_UNKNOWN_EVIDENCE`** - an evidence id in `supportingEvidence` or `citations` does not
   exist in Postgres. This is the anti-hallucination rule: the model cannot invent a document.
2. **`RULE_2_EVIDENCE_NOT_LINKED`** - an evidence item exists but belongs to a different
   transaction. The model cannot borrow another case's proof.
3. **`RULE_3_ACTION_NOT_PERMITTED`** - `recommendedAction` is not in the applicable policy's
   permitted set (or no policy applies at all).
4. **`RULE_4_CONFIDENCE_BELOW_THRESHOLD`** - `confidence < policy.autoPrepareMinConfidence` while
   the action is PREPARE_REPRESENTMENT. Only checked for that action.
5. **`RULE_5_TOO_MANY_CONTRADICTIONS`** - `contradictions.size() > policy.maxContradictions` while
   the action is PREPARE_REPRESENTMENT.
6. **`RULE_6_PROHIBITED_EVIDENCE_TYPE`** - a prohibited evidence type appears in
   `supportingEvidence`.
7. **`RULE_7_DEFENDABLE_WITH_UNSATISFIED_MANDATORY`** - the classification is DEFENDABLE while a
   MANDATORY requirement is unsatisfied. The deterministic readiness view overrules the model's
   optimism.

Any rejection yields `SafetyDecision.DENY`, which routes the case to `AWAITING_HUMAN_REVIEW`. Rules 1
and 2 additionally populate `unsupportedClaims` - the model's own citation text paired with the
reason it could not be backed - and increment `pdei_ai_unsupported_claims_total`. The validator
never mutates anything; it only reports.

### 7.2 `SafetyGate` - the escalation layer

The gate runs the validator first. A DENY is final. Otherwise it applies the policy automation
thresholds (`PolicyEngine.evaluateAction`) and a set of escalation heuristics, and downgrades to
`ALLOW_WITH_REVIEW` when any of these hold:

- a policy threshold failed (confidence, contradictions, readiness floor, or value ceiling);
- the recommended action is anything other than PREPARE_REPRESENTMENT - accepting liability,
  escalating and requesting a policy review are human decisions by nature;
- confidence is below the unattended threshold (0.95 by default, deliberately stricter than the
  policy's auto-prepare floor);
- the dispute value is above `humanReviewAboveAmountMinor`;
- the representment deadline has already passed;
- any CRITICAL readiness gap remains, or a mandatory requirement is still unsatisfied.

Only a clean pass through all of that yields `ALLOW`. Every gate decision - allow, review or deny -
is audited and counted into `pdei_policy_gate_total{decision}`.

`evaluateDeterministic` applies the same thresholds to a proposal the platform generated itself, so
the short-circuited path is governed by exactly the same rules as the AI path.

---

## 8. Admission control (contract 9.4)

`AdmissionController.decide(AdmissionRequest)` answers one question: is this case worth a model call?

**Deterministic short-circuits, evaluated first, in this order:**

1. **PAST_DEADLINE** - the dispute is already past its deadline. Deterministic action
   `ESCALATE_TO_HUMAN`. Checked first because spending money on a case that can no longer be
   submitted is pure waste.
2. **NO_EVIDENCE** - no evidence at all is attached. Deterministic action `ACCEPT_LIABILITY`; a model
   cannot reason about documents that do not exist.
3. **ALL_REQUIREMENTS_SATISFIED** - every MANDATORY requirement is satisfied and there are zero
   contradictions. Deterministic action `PREPARE_REPRESENTMENT`; there is nothing for a model to add.

**Priority formula, for everything that survives:**

```
normalizedFinancialImpact = min(1, amountMinor / financialImpactCapMinor)   // cap 10 000 000 minor
deadlineUrgency           = 1.0 if <= 48h remaining
                          = 0.0 if >= 720h remaining
                          = (720 - hours) / (720 - 48) in between
                          = 0.5 when the deadline is unknown
ambiguityScore            = min(1, (2 * contradictions + gaps) / ambiguityCap)   // cap 8
priority = 100 * ( 0.40 * normalizedFinancialImpact
                 + 0.25 * deadlineUrgency
                 + 0.20 * ambiguityScore
                 + 0.15 * (1 - deterministicConfidence) )      // round half up, clamp 0..100
```

The weighted sum is naturally in [0,1]; it is scaled to the 0-100 range the contract uses for the
threshold and for `POST /v1/admission/score`. Contradictions weigh double gaps in the ambiguity term:
a case that contradicts itself is harder to reason about than one that is merely incomplete.

**Admission** requires `priority >= 55` and a daily budget slot and a token from the bucket. Budget is
consumed before the token and refunded if the token is refused, so a rate-limited case does not
silently burn a day's allowance.

Redis keys (contract 12): `pdei:ai:budget:{yyyy-MM-dd}` (INCR with a two-day TTL, decremented back if
over budget) and `pdei:ai:bucket` (a real token bucket in one atomic Lua script: continuous refill at
`bucketRefillPerSecond` up to `bucketCapacity`).

**Failure mode is closed.** If Redis is unreachable the gate refuses and the case takes the
deterministic path. Failing open would mean unbounded spend on the day the cache is down, and the
deterministic path is always available, so refusing costs correctness nothing.

Every decision, admitted or not, is written to `pdei.ai_admission_log` and counted into
`pdei_ai_admission_total{decision}`. That table is what `GET /api/v1/metrics/funnel` reads.

---

## 9. Evidence lifecycle invariants

- **Content is hashed here, never trusted.** The sha256 stored in Postgres is computed from the bytes
  actually written to MinIO, by `Hashes.sha256`.
- **Nothing is ever overwritten.** `newVersion` creates a new evidence row with a **new evidence id**,
  `parentEvidenceId` pointing at its predecessor and `version = parent.version + 1`; the parent moves
  to `SUPERSEDED` and a `SUPERSEDES` relationship row is written. The object key contains the new id
  and the new version number, so no stored object is ever replaced. This is the deliberate reading of
  "supersedes the parent, SUPERSEDED status": SUPERSEDED is an `EvidenceStatus`, so it must apply to
  an evidence row; `evidence_versions` remains the append-only ledger of stored objects.
- **Creation is idempotent.** The same bytes on the same transaction return the existing artifact
  rather than duplicating it, so replayed or duplicated events are harmless.
- **Write order is object first, row second, event last.** A crash then leaves an orphaned object
  (harmless, reclaimable) rather than a row pointing at nothing (an unfixable integrity failure), and
  no event ever describes state that is not yet durable.
- **Integrity failures invalidate.** `EvidenceIntegrityService.verify` re-reads the object, re-hashes
  it and, on any mismatch or unreadable object, moves the artifact to `INVALIDATED`, publishes
  `EvidenceInvalidated` and writes the finding to the audit chain before returning, so nobody can keep
  using a tampered document. This is also the detector for the simulator's `CORRUPT_EVIDENCE_HASH`
  and `DELETE_EVIDENCE` chaos injections.

MinIO layout (contract 11):

```
pdei-evidence   {merchantId}/{transactionId}/{evidenceType}/{evidenceId}/v{version}/{filename}
pdei-packages   {merchantId}/{caseId}/representment-{caseId}-v{n}.zip
                {merchantId}/{caseId}/manifest.json
```

Every object carries `x-amz-meta-sha256`, `x-amz-meta-source-event-id`, `x-amz-meta-evidence-id` and
`x-amz-meta-version`. Filenames are sanitised by `Buckets.safeFilename`, so a hostile upload name can
never escape its key prefix.

Case assembly selects one live artifact per evidence type (highest version wins), drops prohibited
types, orders mandatory first, verifies the integrity of each selected artifact, then writes a zip
containing `manifest.json` plus `evidence/NN-TYPE-filename` entries. Zip entry timestamps are fixed at
epoch so identical content produces an identical bundle hash.

The audit chain is per merchant, seeded with the constant `GENESIS`. Each entry stores the hash of its
predecessor, and its own hash covers that link, so editing any historical row invalidates every hash
after it. Appends take a short Redis lock on `pdei:lock:audit:{merchantId}` to keep the chain linear;
if Redis is down the append still happens, because a visible fork is far better than a silently
dropped audit entry.

---

## 10. Inbound contracts (what this module consumes)

**Types from `platform-common`** (`docs/SHARED-LIBRARY-API.md` section 1): `Money`, `Ids`,
`CanonicalEvent`, `AuditEvent`, `ActorType`, `AggregateType`, `EventSource`, `EventType`, `Topics`,
`EventHeaders`, `Hashes`, `Json`, `Clocks`, `TimeWindows`, the sealed `PdeiException` subtypes, and
every domain enum (`EvidenceType`, `EvidenceStatus`, `EvidenceSource`, `DisputeReasonCode`,
`DisputeStatus`, `CaseStatus`, `ReadinessBand`, `RequirementStrength`, `GapType`, `GapSeverity`,
`InvestigationClassification`, `RecommendedAction`, `SafetyDecision`).

**Database tables**, read or written through the SPI adapters, in the `pdei` schema owned by
platform-persistence:

| Port | Tables |
|---|---|
| `EvidenceRepositoryPort` | `evidence`, `evidence_versions`, `evidence_relationships` |
| `TransactionRepositoryPort` | `transactions`, `payments`, `orders`, `order_lines`, `shipments`, `deliveries`, `refunds`, `communications` (read only) |
| `PolicyRepositoryPort` | `policies`, `policy_versions`, `evidence_requirements`, plus `disputes` for top reason codes |
| `ReadinessRepositoryPort` | `readiness_snapshots`, `readiness_gaps` |
| `CaseRepositoryPort` | `disputes`, `dispute_cases`, `case_evidence`, `investigations`, `ai_admission_log`, `processed_events` (funnel only) |
| `AuditRepositoryPort` | `audit_events` (append only) |

**Redis keys:** `pdei:lock:audit:{merchantId}`, `pdei:ai:bucket`, `pdei:ai:budget:{yyyy-MM-dd}`.

**HTTP:** `POST /v1/investigate`, `POST /v1/narrative`, `POST /v1/admission/score` and `GET /ready` on
`ai-reasoning-service`, with the `X-PDEI-Service-Token` header.

---

## 11. Outbound contracts (what this module produces)

**Kafka**, through `EventPublisherPort` with the mandatory partition key
`merchantId + ":" + aggregateId`:

| Topic | Events |
|---|---|
| `pdei.evidence.events.v1` | `EvidenceAdded`, `EvidenceExpired`, `EvidenceInvalidated` |
| `pdei.dispute.events.v1` | `DisputeCreated`, `DisputeUpdated`, `DisputeClosed` |
| `pdei.case.events.v1` | `CaseEvidenceAttached` |
| `pdei.audit.events.v1` | `AuditEvent` for every audited action |

Headers on every publish: `pdei-event-id`, `pdei-event-type`, `pdei-merchant-id`,
`pdei-correlation-id`, `pdei-schema-version`.

Publication failures are logged, never thrown: the domain state is already committed by then, and
re-throwing would roll back a correct write because a broker hiccuped.

**Objects:** evidence artifacts in `pdei-evidence`; bundles and manifests in `pdei-packages`.

**Metrics** (Micrometer, contract 13): `pdei_readiness_computation_seconds`,
`pdei_readiness_score{merchant}`, `pdei_evidence_total{type,status}`, `pdei_case_assembly_seconds`,
`pdei_ai_requests_total{provider,outcome}`, `pdei_ai_latency_seconds{provider}`,
`pdei_ai_admission_total{decision}`, `pdei_ai_unsupported_claims_total`,
`pdei_policy_gate_total{decision}`.

**Types consumed by other modules:** every record in `core.model` (api-gateway-service serialises
them straight to JSON), plus the service beans themselves.

---

## 12. Configuration

All properties live under the `pdei.core` prefix (`CoreProperties`). Defaults are the contract values,
so a module that adds the dependency and configures nothing behaves exactly as specified.

| Property | Default | Env var it should be bound to |
|---|---|---|
| `pdei.core.storage.endpoint` | `http://minio:9000` | `PDEI_MINIO_ENDPOINT` |
| `pdei.core.storage.access-key` | `pdei-minio` | `PDEI_MINIO_ACCESS_KEY` |
| `pdei.core.storage.secret-key` | `pdei-minio-secret` | `PDEI_MINIO_SECRET_KEY` |
| `pdei.core.storage.buckets` | `pdei-evidence,pdei-packages` | |
| `pdei.core.storage.ensure-buckets-on-startup` | `true` | |
| `pdei.core.storage.versioning-enabled` | `true` | |
| `pdei.core.storage.presign-ttl` | `15m` | |
| `pdei.core.readiness.expiring-soon-days` | `7` | |
| `pdei.core.readiness.low-quality-threshold` | `0.5` | |
| `pdei.core.readiness.cache-ttl` | `10m` | |
| `pdei.core.readiness.sweep-batch-size` | `500` | |
| `pdei.core.ai.service-url` | `http://ai-reasoning-service:8000` | `PDEI_AI_SERVICE_URL` |
| `pdei.core.ai.service-token` | `dev-service-token` | `PDEI_SERVICE_TOKEN` |
| `pdei.core.ai.connect-timeout` / `read-timeout` | `2s` / `30s` | |
| `pdei.core.ai.max-attempts` / `initial-backoff` / `backoff-multiplier` | `3` / `500ms` / `2.0` | |
| `pdei.core.ai.circuit-failure-threshold` / `circuit-open-duration` | `5` / `60s` | |
| `pdei.core.ai.priority-threshold` | `55` | |
| `pdei.core.ai.financial-impact-cap-minor` | `10000000` | |
| `pdei.core.ai.ambiguity-cap` | `8` | |
| `pdei.core.ai.daily-budget` | `500` | |
| `pdei.core.ai.bucket-capacity` / `bucket-refill-per-second` | `30` / `0.5` | |
| `pdei.core.safety.unattended-confidence` | `0.95` | |
| `pdei.core.audit.lock-ttl` | `30s` | |
| `pdei.core.audit.publish-to-kafka` | `true` | |
| `pdei.core.jdbc.enabled` | `true` | set `false` to supply your own port beans |

Example for a worker's `application.yml`:

```yaml
pdei:
  core:
    storage:
      endpoint: ${PDEI_MINIO_ENDPOINT:http://localhost:9000}
      access-key: ${PDEI_MINIO_ACCESS_KEY:pdei-minio}
      secret-key: ${PDEI_MINIO_SECRET_KEY:pdei-minio-secret}
    ai:
      service-url: ${PDEI_AI_SERVICE_URL:http://localhost:8000}
      service-token: ${PDEI_SERVICE_TOKEN:dev-service-token}
```

---

## 13. Dependencies on other modules

| Dependency | Why |
|---|---|
| `platform-common` | **hard.** Money, ids, events, enums, hashing, JSON, clocks, exceptions. Every one of these names is fixed by `docs/SHARED-LIBRARY-API.md`. |
| `platform-persistence` | schema ownership (Flyway) and `DataSource`/JPA autoconfiguration. This module deliberately imports **no** JPA entity or Spring Data repository; it reaches the schema through its own SPI ports. |
| `spring-boot-starter` | context, binding, logging. No web starter - this is a library. |
| `spring-web` | `RestClient` for the single AI call. Brings no servlet container, so worker modules stay non-web. |
| `spring-boot-starter-data-redis` | `StringRedisTemplate` for the audit lock and the AI budget gate. |
| `spring-kafka` | `KafkaTemplate` for event publication. |
| `io.minio:minio` | object storage. |
| `micrometer-core` | metrics. |
| `spring-boot-starter-validation` | present for consumers and for future bean-validated inputs. Argument checks inside this module are explicit (`CoreErrors.requireText` / `requireValue`) so a failure names the exact field and throws the shared `ValidationException` rather than a `ConstraintViolationException`. |

Consumers: readiness-worker (`ReadinessEngine`), state-builder-worker and
document-processor-service (`EvidenceService`), case-orchestrator-service (`AdmissionController`,
`AiReasoningClient`, `SafetyGate`, `CaseAssemblyService`, `DisputeService`), api-gateway-service (all
read models plus `EvidenceSearchService`), audit-service (`AuditRecorder`), simulator-service
(`EvidenceService`, `EvidenceIntegrityService` for chaos).

---

## 14. How to build and run

evidence-core has no runnable entry point. It is built as part of the backend reactor:

```bash
cd backend
mvn -q -pl evidence-core -am clean install     # build this module and what it depends on
mvn -q -pl evidence-core test                  # run the unit tests only
mvn -q -pl evidence-core -am -DskipTests install
```

The tests are pure JUnit 5 + AssertJ + Mockito. There is **no Spring context, no database, no Redis
and no MinIO in the test suite**, which is why it runs in seconds and is safe to keep in the fast CI
lane.

To use it from a service module:

```xml
<dependency>
  <groupId>com.laserpay.pdei</groupId>
  <artifactId>evidence-core</artifactId>
  <version>${project.version}</version>
</dependency>
```

The two auto-configurations register themselves through
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

1. `CorePersistenceAutoConfiguration` - the JDBC port adapters, active when a `DataSource` exists.
2. `CoreAutoConfiguration` - the domain services, ordered after it.

The split matters: the domain service beans are `@ConditionalOnBean` on the ports, and a nested member
class inside `CoreAutoConfiguration` would be processed *after* the outer class's own bean methods,
so those conditions would evaluate against an empty registry and silently register nothing.

Everything degrades rather than failing:

- no `KafkaTemplate` → `NoOpEventPublisher` logs instead of publishing;
- no `StringRedisTemplate` → `RedisLocks` reports "not acquired" and the AI budget gate is unlimited;
- no `DataSource` → the ports are absent, so the DB-backed services are not registered; the pure
  components (`ReadinessEngine.score`, `GapDetector`, `ContradictionDetector`, `DefaultPolicyMatrix`,
  `AdmissionController`) still work;
- MinIO unreachable at startup → bucket creation logs a warning and the first `put` retries.

---

## 15. Test suite

| Test | Covers |
|---|---|
| `ReadinessEngineTest` | 15 cases: the base ratio, half-weight recommended, round-half-up at 87.5, each of the four penalties, the once-only provenance penalty, non-mandatory gaps not penalising, the clamp at 0, all four band boundaries, empty requirements, unusable statuses, provenance-required requirements |
| `GapDetectorTest` | every gap type, severity ladders, the expiry window edges, superseded chains, contradiction mapping, deterministic ids |
| `ContradictionDetectorTest` | delivery-before-dispatch, delivery-without-dispatch, refund over capture, currency mismatch, address mismatch, address normalisation false-positive guard, quantity mismatch, amount mismatch, clean transaction, evidence-id attribution |
| `AiResultValidatorTest` | one test per contract 9.3 rule, plus the clean ALLOW path, the "rule 4 only applies to preparation" case, and citation validation |
| `AdmissionControllerTest` | all three short-circuits and their precedence, maximum priority, a hand-computed mid-range priority, below-threshold refusal, financial-impact saturation, deadline urgency boundaries, ambiguity weighting, budget exhaustion, rate limiting with refund |
| `DefaultPolicyMatrixTest` | all ten reason codes are covered, the GOODS_NOT_RECEIVED and FRAUDULENT_TRANSACTION matrices, the baseline union, the weight ladder, expiry defaults, default thresholds |
| `BucketsTest` | contract 11 key layout, filename sanitisation, metadata key names, content-type inference |
| `ScoresAndTextTest` | round-half-up, clamping, address normalisation, tsquery construction |

`TestFixtures` provides an `EvidenceView` builder and requirement helpers so the tests read as
scenarios rather than as 21-argument constructor calls.

---

## 16. Extension points

- **Add a contradiction rule.** Add a private `detectX` method to `ContradictionDetector`, call it
  from `detect`, and add a case to `ContradictionDetectorTest`. Nothing else changes: the gap
  detector turns every contradiction into a CONTRADICTORY gap automatically, and the readiness
  penalty follows.
- **Change requirement weights or add a reason code.** Edit `DefaultPolicyMatrix`. `RequirementSpec`
  already carries a per-requirement weight override, so a merchant policy can differ from the seed
  without touching code.
- **Swap persistence.** Implement any `core.spi` port and declare it as a bean; the JDBC adapter backs
  off on `@ConditionalOnMissingBean`. A Spring Data implementation over the platform-persistence
  entities is a drop-in replacement.
- **Swap the AI transport.** Implement `AiReasoningClient` (gRPC, a queue, an in-process mock). The
  admission controller and safety gate are unaffected - they never see the transport.
- **Change the throttling strategy.** Implement `AiBudgetGate`. `AiBudgetGate.unlimited()` is the
  no-throttle case used by tests.
- **Add an evidence relation.** Add a constant to `EvidenceEdge` and use it from
  `EvidenceService.link`; the graph and lineage renderers pass relation names through unchanged.
- **Tune the safety gate.** Escalation heuristics live in one method (`SafetyGate.evaluate`); the
  seven hard rules in `AiResultValidator` should be treated as fixed, since they are normative.
- **Custom readiness inputs.** Implement `ReadinessDataProvider` - for example a simulator provider
  that scores hypothetical evidence sets without writing anything.

---

## 17. Known gaps and TODOs

Ordered by how likely they are to bite a future session.

1. **The SQL column names were this module's assumption, and the assumption was wrong.**
   This item used to read "first integration step: diff those against the migrations and fix the
   six adapter files." That step was never taken, and the first `docker compose up` is what
   collected the bill. **1 of 6 files fixed; 5 remain.**

   `JdbcEvidenceRepository` failed on every single call with

   ```
   BadSqlGrammarException ... column "id" does not exist
   ```

   so `pdei.evidence` was never read or written and the platform held zero evidence - 31 failures
   in state-builder on one seeded run. **No table in schema `pdei` has a column named `id`; every
   primary key is `<entity>_id`.** Three kinds of divergence showed up, and only the first is a
   simple rename:

   - *renames* - `evidence.id` → `evidence_id`, `evidence_versions.id`/`.version` →
     `evidence_version_id`/`version_number`, `evidence_relationships.id`/`.relation` →
     `relationship_id`/`relationship_type`. Now handled with SQL aliases (`evidence_id AS id`) so
     the row mappers keep the record's vocabulary.
   - *a semantic collision* - the query selected `evidence.version` meaning the version number.
     That column exists, and it is the JPA optimistic-lock counter; the version number is
     `current_version`. This would have compiled, run, and returned the wrong integer. **Worse than
     a missing column, because nothing would have failed.**
   - *columns that genuinely did not exist* - `parent_evidence_id`, `quality_score`,
     `provenance_verified`, `status_reason`. Not dead code: they drive the version-chain walk, the
     `UNVERIFIABLE_PROVENANCE` −20 readiness penalty and `LOW_QUALITY` gaps, all of which contract
     §6/§7 already required. Added by `V11__evidence_lineage_quality.sql`.

   **Still to do - the other five adapters, all with the same `id` assumption:**

   | File | bare `id` | other columns that do not exist |
   |---|---|---|
   | `JdbcCaseRepository` | 14× | ~12, incl. `manifest_json` → `package_manifest`, `dispute_amount_minor` → `amount_minor` |
   | `JdbcTransactionRepository` | 10× | ~6, incl. `processor_reference` → `psp_reference` |
   | `JdbcPolicyRepository` | 8× | ~9, incl. **`auto_prepare_min_confidence` → `auto_prepare_min_confidence_bps`** |
   | `JdbcAuditRepository` | 7× | `before_json`/`after_json` → `before_state`/`after_state` |
   | `JdbcReadinessRepository` | 4× | ~7, incl. `penalty_points` → `penalty_total` |

   The policy one is **not** a rename: it is a units change on the threshold that contract §9.3
   rule 4 compares `confidence` against in the AI safety gate. A fraction read as basis points
   would make the gate compare `0.90` to `9000`.

   Enum-set columns (`policy_versions.permitted_actions`, `prohibited_evidence_types`) are still
   assumed to be comma-separated text rather than Postgres arrays - unverified.

   **None of this is reachable from a unit test:** every test in this module stubs the ports, so the
   SQL is only executed against a real database by the integration suite or a running stack. A
   column-name checker belongs in CI alongside the existing architectural greps
   (`docs/testing-strategy.md`).
2. **`PdeiException` constructor shapes are assumed to be `(String message)`.** Every throw goes
   through `core.util.CoreErrors`, so if platform-common's sealed subtypes take something else, that
   single file is the only thing to adapt.
3. **`InvestigationResult.contradictions` element type.** Contract 9.2 shows an empty array and does
   not pin the element shape. This module uses `ContradictionView` and ships a tolerant deserializer
   that also accepts plain strings. `schemas/ai/investigation-result.schema.json` is the referee;
   confirm the Python `pdei_ai.models.investigation.InvestigationResult` agrees.
4. **Metric names are string literals, not `MetricNames` constants.** Contract 13 fixes the metric
   strings but `SHARED-LIBRARY-API.md` does not name the constants on
   `com.laserpay.pdei.common.metrics.MetricNames`. The literals match contract 13 exactly; swap them
   for the constants once platform-common lands.
5. **Id prefixes not in contract 5.** Readiness snapshots use `RDY-`, gaps `GAP-`, package manifests
   `PKG-` and admission log rows `ADM-`, all via `Ids.withPrefix`. If the contract later assigns
   official prefixes, change them in `ReadinessEngine`, `GapDetector`, `CaseAssemblyService` and
   `AdmissionController`.
6. **The parent POM version is assumed to be `0.1.0-SNAPSHOT`** with `artifactId` `pdei-backend`.
   Align with `backend/pom.xml` when the reactor lands.
7. **No integration tests.** There is no Testcontainers coverage for the JDBC adapters, MinIO or the
   Redis token bucket. The unit suite proves the deterministic logic; the adapters are unproven until
   the schema exists. This is the biggest single hole and the natural next piece of work.
8. **`historicalContext` is not assembled here.** `CaseRepositoryPort.merchantWinRate` and
   `similarCaseCount` exist and are implemented, but no service in this module currently builds an
   `InvestigationContext` end to end - case-orchestrator-service assembles it from the pieces this
   module exposes. A future `InvestigationContextFactory` here would remove that duplication.
9. **Readiness caching is configured but not implemented.** `pdei.core.readiness.cache-ttl` and the
   `pdei:readiness:{transactionId}` key of contract 12 exist; `ReadinessEngine` currently recomputes
   every time. Recomputation is cheap and always correct, so this was left for when a measurement
   justifies it (design principle: measure, never claim).
10. **`ReadinessRepositoryPort` is registered but not called by the engine.** Persisting the snapshot
    is readiness-worker's job (it owns the transaction boundary and the `ReadinessRecomputed` event).
    The port is here so that worker does not have to write SQL.
11. **`AiReasoningClient.narrative`** assumes the AI service returns `{"narrative": "..."}` or a bare
    JSON string; it falls back to the deterministic narrative otherwise. Confirm the shape against
    the FastAPI implementation.
12. **`EvidenceService.sweepExpiry` scans by expiry window rather than streaming.** Fine at demo
    scale; it will need keyset pagination if the evidence table grows large.
13. **The audit chain lock is best effort.** If Redis is unavailable two concurrent appends can both
    claim the same predecessor, producing a visible fork that `verifyChain` reports. A unique index on
    `(merchant_id, previous_hash)` in platform-persistence would make this impossible rather than
    merely detectable - worth adding.
14. **Contradiction rule 10 (captured vs order total) only fires for single-order transactions.**
    Split-order transactions would need an allocation model to compare fairly, and guessing one would
    produce false contradictions.
