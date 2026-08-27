# ADR-0007 - Temporal for the dispute case lifecycle

**Status:** Accepted

## Context
A dispute case waits up to seven days for missing evidence, then possibly indefinitely for a
human, then follows up for an outcome that may arrive weeks later (reference doc section 15).
The alternatives are a database state machine driven by cron, or a durable workflow engine.

## Decision
Temporal, namespace `pdei`, task queue `pdei-dispute-cases`, one `DisputeCaseWorkflow` per
case with workflow id `case-{caseId}`. Twelve steps, four signals, two queries
(`PLATFORM-CONTRACT.md` section 10). Workflow code is deterministic - no I/O, no system clock,
no unseeded randomness; every side effect goes through an activity.

## Consequences
- Crash recovery is free. Killing the orchestrator mid-case loses nothing; the workflow
  replays to its prior point. This is directly demonstrable through the chaos console's
  `KILL_WORKER` injection, which is a required demo beat (reference doc section 36).
- Timers, retries and backoff are declarative instead of hand-rolled.
- We accept Temporal's determinism constraints on workflow code, and the operational cost of
  running a Temporal server with its own Postgres schema locally.
- The workflow ID reuse policy makes duplicate `DisputeCreated` deliveries harmless, which is
  required because the event bus is at-least-once.
