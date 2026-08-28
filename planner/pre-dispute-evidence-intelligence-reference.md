# Pre-Dispute Evidence Intelligence

## Project Reference Document

**Working title:** Pre-Dispute Evidence Intelligence  
**Track:** Open Track  
**Primary objective:** Protect merchants from the operational and financial cost of future payment disputes by continuously building a dispute-ready evidence state for transactions, detecting missing or expiring evidence before a dispute exists, and automatically assembling evidence-backed representment cases when disputes arrive.

---

## 1. Executive Summary

### The problem

When a payment dispute/chargeback arrives, the merchant may need to assemble evidence from several independent systems:

- payment records
- orders and invoices
- shipment and delivery systems
- refunds/returns
- customer communication
- merchant policies and terms

The painful part is not merely receiving the dispute. The painful part is that the evidence required to defend it may be scattered, delayed, incomplete, versioned, or already unavailable.

Traditional workflows often begin **after** the dispute arrives.

### The proposed solution

Build a **Pre-Dispute Evidence Intelligence platform** that works continuously in the background.

For eligible transactions, the platform:

1. ingests payment and merchant lifecycle events;
2. normalizes them into a canonical event model;
3. builds an evidence graph and immutable evidence lineage;
4. continuously evaluates **Evidence Readiness**;
5. identifies missing, conflicting, expiring, or weak evidence;
6. learns the evidence patterns relevant to different dispute scenarios;
7. when a dispute arrives, reconstructs the case automatically;
8. invokes Gemini only when ambiguity requires reasoning;
9. validates every AI claim against deterministic evidence;
10. prepares the representment package or escalates to human review.

### Core promise

> **Don't wait for a dispute to start building the defence.**

### Architectural principle

> **Deterministic systems establish financial truth. AI reasons only about ambiguity.**

---

# 2. What This Is - And What It Is Not

## This is

- A proactive evidence-readiness platform.
- A continuously maintained financial evidence graph.
- A reason-code-aware dispute preparation engine.
- A deterministic + agentic hybrid architecture.
- A scalable event-driven system.
- A zero-infrastructure-cost development project using local services and Gemini's free quota.
- A production-oriented architecture demonstrated through synthetic workloads and controlled failure injection.

## This is not

- A generic chatbot.
- A generic document summarizer.
- A fraud detector.
- A payment retry engine.
- A reconciliation dashboard.
- An LLM that decides financial truth.
- An LLM that directly controls money movement.
- A microservice showcase where technologies exist only to look impressive.

---

# 3. Why This Problem

The project is intentionally built around a specific payment-domain gap:

> **The transaction happens today; the dispute may happen weeks later; the evidence required to defend that transaction must survive the entire interval.**

The system therefore changes the workflow from:

```text
Transaction
    ↓
... time passes ...
    ↓
Dispute arrives
    ↓
Merchant hunts for evidence
    ↓
Evidence may be incomplete
```

to:

```text
Transaction
    ↓
Continuous evidence collection
    ↓
Evidence readiness monitoring
    ↓
Gaps / expiry / contradictions identified
    ↓
Dispute arrives
    ↓
Case is already mostly prepared
```

---

# 4. Product Objectives

## Primary objectives

### 4.1 Evidence Readiness

Know, for every relevant transaction:

> "If a dispute occurred right now, how prepared would we be?"

Example:

```text
Transaction: TX-82918
Evidence Readiness: 92%

Payment proof             ✓
Invoice                   ✓
Shipment record           ✓
Delivery confirmation     ✓
Applicable policy         ✓
Customer communication   ⚠
```

### 4.2 Pre-dispute gap detection

Detect:

- missing evidence
- conflicting evidence
- expiring evidence
- invalidated evidence
- evidence whose provenance cannot be established

before a dispute exists.

### 4.3 Automatic case reconstruction

When a dispute arrives, assemble the relevant evidence automatically rather than forcing a merchant/operator to search multiple systems.

### 4.4 AI-assisted investigation

Use Gemini for:

- contextual evidence selection
- contradiction analysis
- ambiguity resolution
- identifying missing evidence
- generating a structured, evidence-backed case narrative

### 4.5 Financial safety

The AI must never be the authority over:

- financial truth
- evidence existence
- policy
- transaction state
- authorization
- money movement

### 4.6 Cost efficiency

The platform must use AI selectively.

Do not send every transaction or every event to Gemini.

Use deterministic systems to reduce enormous volumes of events to a small **uncertainty frontier**.

---

# 5. Design Principles

## 5.1 Deterministic before probabilistic

Use code for:

- amounts
- timestamps
- IDs
- state transitions
- evidence existence
- evidence hashes
- policy evaluation
- readiness calculations
- authorization

Use AI for:

- reasoning
- contextual interpretation
- ambiguity resolution
- hypothesis generation

## 5.2 AI proposes; policy disposes

Gemini produces a structured recommendation.

A deterministic safety/policy layer decides whether that recommendation is allowed.

## 5.3 No unrestricted AI data access

Gemini must never receive unrestricted database access.

It receives a curated `InvestigationContext` and accesses controlled tools.

## 5.4 Evidence must be traceable

Every factual AI claim must map to a real evidence object.

Unsupported evidence references invalidate the result.

## 5.5 Don't introduce infrastructure without a workload

Kafka, Redis, Temporal, object storage, etc. are used only where the problem creates a real need for them.

## 5.6 Scale the expensive layer with ambiguity, not with data volume

The target flow is:

```text
Millions of events
    ↓
Deterministic processing
    ↓
Thousands of candidate cases
    ↓
Hundreds of ambiguous cases
    ↓
Gemini reasoning
```

## 5.7 Start modular, extract only when justified

The core domain begins as a modular Spring Boot application.

Independent deployment/scaling is reserved for workloads that genuinely require it.

---

# 6. High-Level Architecture

```text
                         ┌──────────────────────────┐
                         │       Merchant UI        │
                         │                          │
                         │ Control Tower            │
                         │ Case X-Ray               │
                         │ Evidence Readiness       │
                         │ Simulation / Chaos       │
                         └────────────┬─────────────┘
                                      │
                               HTTPS / WebSocket
                                      │
                         ┌────────────▼─────────────┐
                         │       API Layer          │
                         │       Spring Boot        │
                         └────────────┬─────────────┘
                                      │
              ┌───────────────────────┼────────────────────────┐
              │                       │                        │
              ▼                       ▼                        ▼
       Merchant / Case         Evidence Query           Simulation API
          APIs                    APIs
              │                       │
              └──────────────┬────────┘
                             │
                             ▼
                  ┌─────────────────────┐
                  │    Kafka Event Bus  │
                  └─────────┬───────────┘
                            │
          ┌─────────────────┼──────────────────┐
          │                 │                  │
          ▼                 ▼                  ▼
   Normalization       State Builder       Audit/Event
     Workers             Workers            Consumers
          │                 │
          └────────────┬────┘
                       │
              ┌────────▼────────┐
              │ Evidence Domain │
              │     Engine      │
              └────────┬────────┘
                       │
       ┌───────────────┼───────────────────┐
       │               │                   │
       ▼               ▼                   ▼
  PostgreSQL         Redis              MinIO
  operational       hot state        evidence blobs
  truth
       │               │                   │
       └───────────────┼───────────────────┘
                       │
                       ▼
              ┌──────────────────┐
              │ Readiness Engine │
              │ + Policy Engine  │
              └────────┬─────────┘
                       │
                       ▼
               ┌───────────────┐
               │ Dispute Event │
               └───────┬───────┘
                       │
                       ▼
             ┌────────────────────┐
             │ Case Orchestrator  │
             │     / Temporal     │
             └─────────┬──────────┘
                       │
                       ▼
             ┌────────────────────┐
             │ Evidence Resolver  │
             └─────────┬──────────┘
                       │
                ambiguity?
                  /          \
                no            yes
                │              │
                ▼              ▼
        Deterministic      AI Reasoning
           path               Service
                               │
                               ▼
                            Gemini
                               │
                               ▼
                     Structured Investigation
                               │
                               ▼
                     Policy / Safety Gate
                       /             \
                      /               \
                auto-prepare       human review
                     │                   │
                     └─────────┬─────────┘
                               ▼
                    Representment Package
                               │
                               ▼
                         Final Audit Log
```

---

# 7. Architectural Planes

The architecture is split into three logical planes.

## 7.1 Truth Plane

Responsible for financial and evidentiary truth.

```text
Kafka
PostgreSQL
MinIO
Evidence domain model
```

## 7.2 Intelligence Plane

Responsible for contextual reasoning.

```text
FastAPI
Gemini
Tool orchestration
Investigation context
Structured reasoning
```

## 7.3 Control Plane

Responsible for what the system is allowed to do.

```text
Policy engine
Temporal workflows
Authorization
Safety gates
Audit
Human review
```

Conceptually:

```text
              ┌─────────────────────┐
              │     CONTROL PLANE   │
              │ policy / workflow   │
              │ authorization/audit │
              └──────────┬──────────┘
                         │
          ┌──────────────┴──────────────┐
          ▼                             ▼
┌────────────────────┐        ┌────────────────────┐
│    TRUTH PLANE     │        │ INTELLIGENCE PLANE │
│                    │        │                    │
│ Kafka              │        │ Gemini             │
│ PostgreSQL         │        │ Investigation      │
│ MinIO              │        │ Tooling            │
│ Evidence Graph     │        │ Reasoning          │
└────────────────────┘        └────────────────────┘
```

---

# 8. Domain Model

The core domain contains:

```text
Merchant
Customer
Transaction
Payment
Order
Invoice
Shipment
Delivery
Refund
Communication
Policy
Evidence
Dispute
DisputeCase
Investigation
EvidenceRequirement
EvidenceReadiness
AuditEvent
```

Conceptual relationship:

```text
                 Transaction
                /     |      \
               /      |       \
          Payment    Order    Refund
                       |
                    Shipment
                       |
                    Delivery
                       |
                  Evidence
                       |
                    Dispute
```

---

# 9. Event Backbone

## Kafka

Canonical event types:

```text
PaymentCreated
PaymentAuthorized
PaymentCaptured
PaymentFailed

OrderCreated
OrderFulfilled
OrderCancelled

ShipmentCreated
ShipmentDispatched
ShipmentDelivered

RefundCreated
RefundProcessed

CommunicationCreated
CommunicationReceived

EvidenceAdded
EvidenceExpired
EvidenceInvalidated

DisputeCreated
DisputeUpdated
DisputeClosed
```

Each event contains:

```text
eventId
eventType
aggregateId
merchantId
occurredAt
observedAt
schemaVersion
source
payload
correlationId
```

### Partitioning

Use a stable domain aggregate key so events requiring ordering remain together.

Example:

```text
merchantId + aggregateId
```

Exact partitioning strategy should be validated against the final consistency requirements.

---

# 10. Event Normalization

External systems are expected to produce different event schemas.

Therefore:

```text
Raw Source Event
      ↓
Schema Validation
      ↓
Source Adapter
      ↓
Canonical Domain Event
```

The rest of the platform should operate on canonical events rather than source-specific payloads.

---

# 11. Evidence Engine

The Evidence Domain Engine is responsible for:

- creating evidence objects
- linking evidence to financial entities
- tracking versions
- tracking provenance
- invalidating or expiring evidence
- building relationships
- exposing evidence to the readiness and investigation layers

## Evidence metadata

```text
evidenceId
type
source
objectKey
sha256
version
createdAt
observedAt
parentVersion
sourceEventId
```

Evidence files themselves are stored in MinIO.

PostgreSQL stores their metadata and lineage.

---

# 12. Evidence Integrity

Every artifact receives:

- SHA-256 content hash
- source identifier
- source event
- creation timestamp
- observation timestamp
- version
- parent version where applicable

Historical versions must not be silently overwritten.

This supports:

- provenance
- integrity verification
- historical reconstruction
- auditability

---

# 13. Evidence Readiness Engine

For every relevant transaction, maintain a readiness state.

Example:

```text
Transaction: TX-82918

Evidence Readiness: 92%

Payment proof             ✓
Invoice                   ✓
Shipment record           ✓
Delivery confirmation     ✓
Applicable policy         ✓
Customer communication   ⚠
```

Readiness is deterministic.

The readiness engine should be able to answer:

- What evidence is present?
- What is missing?
- What has expired?
- What is contradictory?
- What evidence would be required under a potential dispute reason?
- What would prevent automated preparation?

---

# 14. Policy Engine

The policy engine maps dispute scenarios to evidence requirements and permissible actions.

Example:

```text
Reason:
GOODS_NOT_RECEIVED

Required:
- payment confirmation
- invoice
- shipping evidence
- delivery evidence
- applicable policy
```

The policy engine also defines:

- optional evidence
- prohibited evidence
- automation thresholds
- expiry rules
- escalation conditions

The LLM cannot override the policy engine.

---

# 15. Long-Running Workflow

## Temporal

A dispute workflow can persist for days or weeks.

Typical lifecycle:

```text
Dispute created
      ↓
Evidence retrieval
      ↓
Evidence gap detection
      ↓
Investigation
      ↓
Missing evidence wait
      ↓
Human approval if required
      ↓
Case preparation
      ↓
Submission
      ↓
Follow-up
      ↓
Closure
```

Temporal provides:

- durable workflows
- retries
- timers
- recovery
- workflow state
- replayability

---

# 16. AI Reasoning Architecture

AI sits behind a strict boundary.

```text
InvestigationContext
        ↓
Python / FastAPI
        ↓
Tool orchestration
        ↓
Gemini
        ↓
Structured InvestigationResult
```

The model receives curated context rather than unrestricted database access.

## InvestigationContext

```text
dispute reason
transaction summary
relevant evidence
evidence requirements
contradictions
missing evidence
policy constraints
historical context
```

---

# 17. AI Tools

Gemini can call controlled read-only tools such as:

```text
getTransaction()
getOrder()
getShipment()
getRefund()
getEvidence()
findRelatedEvidence()
findContradictions()
getApplicablePolicy()
getEvidenceRequirements()
getTimeline()
```

AI cannot directly:

```text
write financial state
modify evidence
change transaction state
approve money movement
submit a dispute
```

---

# 18. Structured AI Output

Gemini must produce a schema-constrained result.

Example:

```json
{
  "classification": "DEFENDABLE",
  "confidence": 0.973,
  "supportingEvidence": [
    "EV-1092",
    "EV-8821"
  ],
  "missingEvidence": [],
  "contradictions": [],
  "reasoningSummary": "Delivery is supported by...",
  "recommendedAction": "PREPARE_REPRESENTMENT"
}
```

Before accepting the result, the backend must validate:

- all evidence IDs exist;
- every evidence item belongs to the correct case;
- the recommended action is policy-permitted;
- confidence thresholds are satisfied;
- contradictory evidence is accounted for.

---

# 19. AI Admission Control

Gemini is a selective reasoning layer.

Flow:

```text
All Events
    ↓
Deterministic Processing
    ↓
Evidence Completeness
    ↓
Contradiction Detection
    ↓
Simple Cases Resolved
    ↓
Ambiguous Cases
    ↓
Priority Queue
    ↓
Gemini
```

Priority should consider factors such as:

```text
financial impact
deadline urgency
ambiguity
confidence uncertainty
```

The system should optimize model usage rather than calling Gemini for everything.

---

# 20. Safety Model

Core rule:

> **AI proposes; deterministic policy disposes.**

Example:

```text
Gemini:
confidence = 98.4%
recommendedAction = PREPARE_REPRESENTMENT
```

Policy layer:

```text
allowed = true
```

→ proceed.

Another:

```text
Gemini:
confidence = 72%
contradictions = 2
```

Policy layer:

```text
allowed = false
```

→ human review.

---

# 21. Data Layer

## PostgreSQL

Primary source of structured operational truth.

Core domains:

```text
merchants
customers

transactions
payments
orders
refunds
shipments
communications

evidence
evidence_relationships
evidence_versions

policies
evidence_requirements

disputes
dispute_cases
case_evidence

readiness_snapshots

investigations
investigation_findings

audit_events
```

### Money representation

Never use floating-point money.

Use integer minor units:

```text
BIGINT amount_in_minor_units
CHAR(3) currency
```

---

# 22. Object Storage

## MinIO

Local S3-compatible object storage.

Examples:

```text
invoice.pdf
shipping-label.pdf
delivery-proof.pdf
refund-receipt.pdf
customer-email.eml
terms-and-policy.pdf
```

PostgreSQL stores:

- object key
- content hash
- metadata
- source
- timestamps
- version

---

# 23. Redis

Use Redis only for hot/ephemeral state:

```text
active case state
readiness cache
recent aggregate state
idempotency keys
distributed locks
rate limiting
Gemini admission control
```

Redis must never be treated as the authoritative financial store.

---

# 24. Search

Initial implementation:

## PostgreSQL Full-Text Search

Only introduce OpenSearch/Elasticsearch later if real workload or benchmark results demonstrate a need.

---

# 25. Document Processing

Use:

- **Apache Tika**
- **Apache PDFBox**

Purpose:

- metadata extraction
- text extraction
- document normalization

OCR should only be added when a genuine document workflow requires it.

The first prototype should use mostly structured synthetic evidence so that the core system is tested rather than an OCR pipeline.

---

# 26. Frontend

## Next.js + TypeScript

Primary screens:

### Merchant Control Tower

Show:

- Evidence Readiness
- open disputes
- at-risk transactions
- expiring evidence
- cases requiring review

### Case X-Ray

Show:

- timeline
- evidence graph
- evidence provenance
- contradictions
- AI reasoning
- recommended action

### Simulation / Chaos Console

Allow judges/developers to:

```text
Inject delayed event
Inject duplicate event
Delete evidence
Create dispute
Replay events
Kill worker
Restore worker
```

---

# 27. Observability

## OpenTelemetry

Distributed tracing across:

```text
API
Kafka
workers
database
Temporal
AI service
Gemini
```

## Prometheus

Metrics such as:

```text
events/sec
consumer lag
event processing latency

readiness computation latency
case assembly latency

AI requests/minute
AI success rate

Gemini admission rate

evidence completeness
case completion rate
workflow failures
```

## Grafana

Operational and scale dashboards.

## Loki

Centralized structured logs.

---

# 28. Simulation and Load Testing

The project requires its own synthetic financial world.

Generate:

```text
merchants
customers
orders
payments
refunds
shipments
communications
evidence
disputes
```

Inject realistic failure modes:

```text
late events
duplicate events
out-of-order events
missing documents
expired evidence
conflicting sources
partial refunds
multiple shipments
policy changes
```

The simulator must support deterministic seeds.

Example:

```bash
simulate --seed 4281 --transactions 10000000
```

This allows repeatable benchmarks.

---

# 29. Scale Strategy

The system should be designed around a funnel:

```text
Millions of events
        ↓
Canonical event processing
        ↓
Evidence state updates
        ↓
Readiness computation
        ↓
Potential dispute candidates
        ↓
Ambiguous cases
        ↓
AI reasoning
        ↓
Human escalation
```

This is intentional.

The AI layer should not scale linearly with event volume.

---

# 30. Local Development Architecture

The entire stack should run locally.

Target environment:

```text
Windows
  ↓
WSL2
  ↓
Ubuntu
  ↓
Docker / Docker Compose
```

Local containers:

```text
Spring Boot
Kafka
PostgreSQL
Redis
MinIO
Temporal
Prometheus
Grafana
Loki
FastAPI
Next.js
```

Gemini is the only external reasoning dependency.

Development target:

> **₹0 infrastructure cost.**

---

# 31. Production Deployment Model

Local development:

```text
Docker Compose
```

Production-oriented deployment:

```text
Kubernetes
```

Conceptual topology:

```text
                    Load Balancer
                         │
                ┌────────┴────────┐
                ▼                 ▼
             API pods          WebSocket
                │
                ▼
              Kafka
                │
        ┌───────┼────────┐
        ▼       ▼        ▼
    Workers  Workers   Workers
        │       │        │
        └───────┼────────┘
                │
       PostgreSQL / Redis
                │
              MinIO
```

Worker autoscaling should primarily consider:

> **Kafka consumer lag**

rather than CPU alone.

---

# 32. Final Technology Stack

| Layer | Technology | Purpose |
|---|---|---|
| Core backend | **Java 21 + Spring Boot 3** | Financial/evidence domain |
| API | **REST + WebSocket/SSE** | APIs + live UI updates |
| Event bus | **Apache Kafka** | Durable event streaming/replay |
| Kafka integration | **Spring Kafka** | Java/Kafka integration |
| Primary DB | **PostgreSQL** | Source of structured truth |
| Cache/hot state | **Redis** | Caching, idempotency, locks, rate limiting |
| Object storage | **MinIO** | Evidence artifacts |
| Workflow | **Temporal** | Long-running dispute workflows |
| AI service | **Python + FastAPI** | AI orchestration boundary |
| AI model | **Gemini 3.5 Flash-Lite** | Selective ambiguity reasoning |
| AI validation | **Pydantic + JSON Schema** | Strict structured output |
| Document processing | **Apache Tika + PDFBox** | Evidence extraction |
| Search | **PostgreSQL FTS initially** | Evidence search |
| Frontend | **Next.js + TypeScript** | Merchant application |
| UI | **Tailwind CSS + shadcn/ui** | Enterprise interface |
| Tracing | **OpenTelemetry** | Distributed tracing |
| Metrics | **Prometheus** | Operational metrics |
| Dashboards | **Grafana** | Observability |
| Logs | **Loki** | Centralized logs |
| Containers | **Docker + Docker Compose** | Local zero-cost environment |
| Dev OS | **WSL2 + Ubuntu** | Linux-native environment |
| Java testing | **JUnit 5** | Unit testing |
| Integration testing | **Testcontainers** | Real infrastructure tests |
| Python testing | **Pytest** | AI-service tests |
| Load testing | **Gatling** | High-throughput testing |
| Chaos | **Custom simulator/chaos engine** | Failure injection |
| Java build | **Maven** | Build/dependency management |
| Python environment | **uv** | Python dependency management |
| Version control | **Git + GitHub** | Source control |
| CI | **GitHub Actions** | Automated validation |

---

# 33. Deliberately Excluded Technologies

## No Neo4j initially

PostgreSQL relationships are sufficient for the first implementation.

## No Elasticsearch/OpenSearch initially

PostgreSQL FTS is sufficient until benchmarks prove otherwise.

## No Kubernetes locally

Docker Compose is the local deployment mechanism.

## No local LLM

Gemini is the primary reasoning provider.

The AI provider must nevertheless be abstracted behind an interface so the system remains model-agnostic.

---

# 34. AI Provider Abstraction

Conceptual interface:

```text
EvidenceReasoner
       │
       ├── GeminiReasoner
       │
       └── MockReasoner
```

The application domain must not depend directly on Gemini.

Development mode can use the mock implementation.

Final demonstration can use Gemini.

This protects the project against quota exhaustion and makes testing deterministic.

---

# 35. Demo Philosophy

The system should be demonstrated as a **real event-driven financial platform**, not as a static dashboard.

The demo should show:

1. a transaction becoming evidence-ready;
2. evidence accumulating over time;
3. a missing/expiring artifact being detected;
4. a dispute being injected;
5. automatic evidence retrieval;
6. deterministic cases bypassing AI;
7. ambiguous cases going to Gemini;
8. AI reasoning with evidence references;
9. safety validation;
10. human escalation where confidence is insufficient;
11. the evidence package becoming ready;
12. the entire process being observable through traces/metrics.

---

# 36. Chaos Demonstration

The demo must allow deliberate system failures:

```text
Duplicate Event
Delayed Event
Out-of-Order Event
Missing Evidence
Conflicting Evidence
Worker Failure
Consumer Restart
Event Replay
```

The goal is to demonstrate:

- idempotency
- eventual consistency
- replayability
- durable workflow recovery
- evidence integrity
- safe AI behavior

---

# 37. Benchmark Philosophy

Do not claim production performance.

Use synthetic workloads with clearly labelled metrics.

Potential benchmark dimensions:

```text
event throughput
consumer lag
evidence processing latency
readiness computation latency
case assembly latency
AI invocation rate
AI latency
evidence completeness
unsupported-claim rate
duplicate suppression
workflow recovery time
```

Example target presentation:

```text
Synthetic benchmark
10M transactions
40M+ events

Evidence completeness:        XX%
Case assembly p95:            XX ms
Duplicate suppression:        XX%
Unsupported AI claims:        XX%
AI invocation reduction:      XX%
Replay recovery:              XX%
```

All benchmark values must be measured by the actual implementation rather than invented for the presentation.

---

# 38. Success Criteria

The project succeeds when it can credibly demonstrate:

### Product

- merchants can see evidence readiness;
- evidence gaps are detected proactively;
- disputes can be reconstructed automatically;
- case preparation is significantly faster than a manual workflow.

### Engineering

- events are processed asynchronously;
- duplicate and out-of-order events are handled;
- state can be replayed;
- evidence is immutable/versioned;
- workflows survive worker failures;
- the system scales horizontally.

### AI

- Gemini is invoked selectively;
- every generated factual claim references actual evidence;
- unsupported claims are rejected;
- contradictory evidence lowers confidence;
- low-confidence cases escalate safely.

### Economics

- no paid infrastructure is required for the prototype;
- AI usage remains within the available Gemini free quota;
- deterministic processing minimizes model calls.

---

# 39. Non-Negotiable Rules for Future Implementation

These rules must survive all future refactors.

1. **Never let the LLM become the source of truth.**
2. **Never allow the LLM to directly mutate financial state.**
3. **Never invent evidence.**
4. **Never use floating-point values for monetary amounts.**
5. **Never add a technology merely to make the architecture diagram larger.**
6. **Prefer a simple correct implementation before introducing a distributed component.**
7. **Keep AI provider-specific code isolated behind an abstraction.**
8. **Preserve provenance and auditability for every evidence artifact.**
9. **Design all event consumers to tolerate duplicates.**
10. **Assume events can arrive late or out of order.**
11. **Make workloads reproducible with deterministic simulation seeds.**
12. **Measure performance instead of making unsupported scalability claims.**
13. **Keep the core financial domain in Java.**
14. **Keep AI reasoning isolated in Python.**
15. **Keep the project runnable locally for ₹0.**

---

# 40. One-Sentence Project Definition

> **Pre-Dispute Evidence Intelligence is an event-driven financial control platform that continuously constructs and verifies dispute-ready evidence for merchant transactions, detects evidence gaps before disputes occur, and uses selective AI reasoning to assemble safe, evidence-backed representment cases at scale.**

---

# 41. Core Engineering Thesis

The project is built around one central idea:

> **The transaction is short-lived. The evidence required to defend the transaction is a long-lived state that must be continuously maintained.**

Everything in the architecture follows from that idea.

---

# 42. Mental Model for Future Development

Whenever proposing a new feature, architecture change, or technology, ask:

```text
Does it solve a real merchant problem?
        ↓
Does it improve evidence readiness or dispute resolution?
        ↓
Does it require this level of complexity?
        ↓
Can it remain deterministic where possible?
        ↓
Does AI genuinely add reasoning value?
        ↓
Can we test it with synthetic ground truth?
        ↓
Can it run locally for ₹0?
```

If the answer is no, reconsider the feature.

---

# 43. Current Status

**Project direction:** Selected  
**Track:** Open Track  
**Primary product:** Pre-Dispute Evidence Intelligence  
**Core backend:** Java / Spring Boot  
**AI:** Gemini 3.5 Flash-Lite  
**Infrastructure:** Local Docker/WSL2  
**Primary database:** PostgreSQL  
**Event backbone:** Kafka  
**Workflow engine:** Temporal  
**Object storage:** MinIO  
**Cache:** Redis  
**Frontend:** Next.js  
**Observability:** OpenTelemetry + Prometheus + Grafana + Loki  
**Target prototype cost:** ₹0  
**Next phase:** Detailed repository/module design and implementation planning
