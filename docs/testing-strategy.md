# Testing Strategy

What we test, at which level, and why. The guiding rule: **the properties in
`architecture.md` section 6 are the specification.** Every one of them has a test that would
fail if the property broke.

---

## 1. The test pyramid, and where it bulges

```
                 /\
                /  \      chaos / demo scenarios  (simulator-driven, manual + scripted)
               /----\
              /      \    integration (Testcontainers: Postgres, Kafka, Redis, MinIO)
             /--------\
            /          \   slice tests (MockMvc, Temporal TestWorkflowEnvironment, respx)
           /------------\
          /              \  unit (pure domain logic - the widest layer)
         /----------------\
```

The bulge is deliberate: `evidence-core` holds nearly all the logic worth getting wrong, and
almost none of it needs infrastructure to test. Readiness scoring, gap detection,
contradiction detection and the seven AI validation rules are pure functions over inputs.

---

## 2. Unit tests - the domain

**`ReadinessEngine`** is the highest-value test target in the repository. It gets a table of
at least eight cases covering:

- all mandatory satisfied, nothing recommended -> exactly 100
- partial mandatory satisfaction -> the weighted fraction, verified by hand
- each of the four penalty rules in isolation (contradiction -15, expired mandatory -10,
  expiring-soon mandatory -5, unverifiable provenance -20)
- penalties stacking below zero -> clamps at 0, never negative
- the band boundaries at exactly 90, 89, 75, 74, 50, 49
- round-half-up behaviour at `.5`

If someone changes the formula, this table fails loudly. That is the point: the score is a
published number a merchant acts on, so a silent change is a defect even if the new formula is
"better" (ADR-0006).

**`AiResultValidator`** gets one test per rejection rule in `PLATFORM-CONTRACT.md` section 9.3
- seven tests, each asserting `SafetyDecision.DENY` and the specific reason string. Plus one
test asserting a clean result yields `ALLOW`. Rule 7 deserves special attention: high
confidence must not rescue an unsatisfied MANDATORY requirement.

**`AdmissionController`** - the priority formula at its boundaries (just under and just over
55), and each of the three deterministic short-circuits.

**`ContradictionDetector`** - delivery before dispatch; refund exceeding capture; delivery
address not matching the order's shipping address; quantity mismatch across shipments.

**`Money`** - arithmetic, currency-mismatch throwing, and the display formatting for a
zero-exponent currency (JPY) and a three-exponent one (KWD). This is the test that catches a
hardcoded divide-by-100.

**`Hashes.chain`** - determinism, and that reordering fields does not change the canonical
JSON hash.

---

## 3. Integration tests - Testcontainers

Real Postgres, real Kafka, real Redis, real MinIO. No mocks at this level; the whole point is
to catch what mocks hide.

- **Migrations** run clean from empty on every build. `V1` through `V10` apply in order.
- **`ProcessedEventRepository.markProcessed`** - concurrent callers, exactly one gets
  `firstTime = true`. This is the idempotency primitive; if it is wrong, nothing above it is
  safe.
- **Consumer idempotency** - publish the same `CanonicalEvent` fifty times, assert the
  projection is identical to a single delivery and `pdei_events_duplicate_total` rose by 49.
- **Out-of-order rejection** - apply event at T+10, then deliver the event from T+5, assert
  state did not regress.
- **Evidence versioning** - write v1, write v2, assert v1 is retrievable and `SUPERSEDED`,
  assert `parent_version` links, assert the MinIO object keys differ by version segment.
- **Integrity detection** - corrupt the stored object behind MinIO's back, run
  `EvidenceIntegrityService`, assert `INVALIDATED` plus an emitted event plus an audit row.
- **Audit chain** - append N events, verify the chain; then tamper with row K and assert
  `ChainVerifier` reports the first divergence at exactly K.
- **Full-text search** - index extracted text, assert `websearch_to_tsquery` finds it and that
  results are scoped to the right merchant.

---

## 4. Slice tests

**MockMvc** per controller - status codes, validation rejection, pagination shape, and error
mapping through `GlobalExceptionHandler`.

One structural test earns its place: **assert that no mutating HTTP verb exists anywhere under
`/api/v1/ai-tools`**, by reflecting over the controller's request mappings rather than by
reading the code. ADR-0005 claims the AI cannot write; this is the test that keeps the claim
true after a future refactor.

**Temporal `TestWorkflowEnvironment`** - four workflow paths:

1. happy path, deterministic bypass, auto-prepare;
2. missing evidence, timer fires, `evidenceArrived` signal arrives, workflow resumes;
3. AI invoked, safety gate denies, workflow routes to human review;
4. human rejects, workflow closes without submission.

Time is skipped, so the seven-day wait tests in milliseconds.

**`respx`** for the Python service - upstream tool calls mocked at the HTTP layer, asserting
the tool client sends `X-PDEI-Service-Token` and never issues a non-GET request.

---

## 5. Python (pytest)

- Pydantic schema validation: confidence outside `[0, 1]` rejected; evidence IDs not matching
  the `EV-` prefix rejected; unknown enum values rejected.
- **`MockReasoner` determinism**: same `investigationId` in, byte-identical result out, run
  100 times. No wall clock, no unseeded randomness anywhere in the output path.
- **Unsupported-claim filter**: feed a reasoner response citing `EV-NONEXISTENT`, assert the
  claim is dropped before the result leaves the service and the counter increments.
- **Tool executor**: unknown tool name rejected; an attempted POST rejected structurally.
- Admission scoring parity: the Python implementation and the Java implementation must return
  the same priority for the same inputs. A shared fixture file drives both.

---

## 6. Frontend

- **Type-level**: `tsc --noEmit` is a real test here. The API client is typed against the
  contract's response shapes, so an endpoint that drifts fails the build.
- **Component**: `ReadinessMeter` banding, `MoneyDisplay` currency exponents (again - money
  formatting gets tested in every language that touches it), `StatusBadge` covering every enum
  member.
- **Mock mode**: with `NEXT_PUBLIC_USE_MOCKS=true` every route renders without a backend. This
  is a smoke test and an onboarding affordance at once.

---

## 7. Chaos as a test suite

The chaos injections are not only a demo; they are the acceptance tests for the distributed
properties. Each maps to a property:

| Injection | Asserts |
|---|---|
| `DUPLICATE_EVENT` | readiness score unchanged after N duplicate deliveries |
| `OUT_OF_ORDER_EVENT` | projection does not regress |
| `DELAYED_EVENT` | `observedAt - occurredAt` gap preserved and visible |
| `DROP_EVENT` | gap detection notices the resulting hole |
| `CORRUPT_EVIDENCE_HASH` | evidence invalidated, readiness drops, audit written |
| `KILL_WORKER` | Temporal workflow resumes at its prior step |
| `REPLAY_EVENTS` | rebuilt projections yield identical scores |

`scripts/smoke-test.sh` runs the cheap ones in CI; the worker-killing ones run locally.

---

## 8. What we deliberately do not test

- Gemini's output quality. It is non-deterministic and not ours. We test that whatever it
  returns is validated, cited, and gated - which is the only property that matters for safety.
- Production throughput. Benchmarks are synthetic and labelled as such (see
  `benchmark-plan.md`); we measure, we do not claim.
- Carrier, PSP, and CRM integrations. `SimulatedNetworkSubmitter` is a named seam, not a
  pretend implementation.

---

## 9. CI gates

`.github/workflows/ci.yml` must fail the build on any of:

- Maven test failure (including Testcontainers integration tests);
- `ruff` / `mypy` / `pytest` failure;
- `tsc --noEmit` or `eslint` failure;
- a grep hit for `double`, `float`, or `BigDecimal` adjacent to an amount identifier;
- a grep hit for `LocalDateTime` in domain code;
- a Gemini SDK import anywhere under `backend/`;
- `docker compose config` failing to validate.

The last three are cheap greps that enforce architectural rules no type system can.
