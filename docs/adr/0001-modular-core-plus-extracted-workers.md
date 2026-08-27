# ADR-0001 - Modular core plus extracted workers, not full microservices

**Status:** Accepted

## Context
The reference document pulls in two directions: the system must be event-driven and
horizontally scalable, and it must not become "a microservice showcase where technologies
exist only to look impressive" (reference doc section 2). Principle 5.7 resolves the tension:
start modular, extract only when justified.

## Decision
The financial domain lives in shared library modules - `platform-common`,
`platform-persistence`, `evidence-core` - compiled into every deployable. Separate
deployables exist only where a workload genuinely differs in scaling shape or failure domain:

- `ingestion-service` - bursty, public-facing, must absorb spikes independently;
- `normalization-worker`, `state-builder-worker`, `readiness-worker` - Kafka consumers that
  scale on consumer lag, independently of each other;
- `case-orchestrator-service` - hosts Temporal workers, a distinct runtime concern;
- `document-processor-service` - CPU-heavy and slow; must not block the event pipeline;
- `ai-reasoning-service` - a different language, and a security boundary (ADR-0005);
- `api-gateway-service` - request/response plus WebSocket fan-out, scales on connections;
- `simulator-service` - a test harness, never deployed alongside production traffic.

## Consequences
- Domain logic exists once, in `evidence-core`, and cannot drift between services.
- A change to readiness scoring requires rebuilding several services. Accepted: correctness of
  a single shared implementation beats independent deployability of a formula.
- Services share one PostgreSQL database rather than owning private stores. This departs from
  textbook microservice guidance and is deliberate - the evidence graph is one consistency
  domain, and splitting it would require distributed transactions to preserve financial truth.
