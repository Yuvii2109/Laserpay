/**
 * The chaos catalogue: one entry per `ChaosType` in contract 6.
 *
 * Each entry says what the injection does, what it *proves* if the platform survives it, and
 * what target selector `POST /sim/v1/chaos` needs. The target shape is free-form on the wire
 * (`ChaosRequest.target`), so this file is the console's contract with simulator-service about
 * which key each type expects.
 */
import { CHAOS_TYPES } from '@/lib/types/simulation';
import type { ChaosType } from '@/lib/types/simulation';

export type ChaosFamily = 'event-stream' | 'evidence' | 'infrastructure' | 'dispute';

export const CHAOS_FAMILY_LABEL: Readonly<Record<ChaosFamily, string>> = {
  'event-stream': 'Event stream',
  evidence: 'Evidence integrity',
  infrastructure: 'Infrastructure',
  dispute: 'Dispute injection',
};

export const CHAOS_FAMILY_BLURB: Readonly<Record<ChaosFamily, string>> = {
  'event-stream':
    'Kafka is at-least-once and unordered in practice. These prove consumers are idempotent and tolerate late arrival (contract 17, rules 9 and 10).',
  evidence:
    'Evidence is hashed at capture. These prove tampering, deletion and expiry are detected rather than silently absorbed.',
  infrastructure:
    'Workers die and consumers fall behind. These prove nothing is lost and nothing is processed twice.',
  dispute: 'Creates real work for the case pipeline so the whole path can be watched end to end.',
};

/** Which selector the target object needs. */
export type TargetKind = 'transaction' | 'evidence' | 'service' | 'topic' | 'none';

export interface ChaosSpec {
  type: ChaosType;
  label: string;
  family: ChaosFamily;
  /** What the injection actually does to the platform. */
  effect: string;
  /** What surviving it demonstrates. */
  proves: string;
  target: TargetKind;
  /** Field name inside `ChaosRequest.target`. */
  targetKey: string;
  usesDelay: boolean;
  usesCount: boolean;
}

export const CHAOS_CATALOG: Readonly<Record<ChaosType, ChaosSpec>> = {
  DUPLICATE_EVENT: {
    type: 'DUPLICATE_EVENT',
    label: 'Duplicate event',
    family: 'event-stream',
    effect: 'Redelivers the transaction’s events on the raw topic.',
    proves: 'Redis SETNX plus the processed_events table drop the repeats; no score moves twice.',
    target: 'transaction',
    targetKey: 'transactionId',
    usesDelay: false,
    usesCount: true,
  },
  DELAYED_EVENT: {
    type: 'DELAYED_EVENT',
    label: 'Delayed event',
    family: 'event-stream',
    effect: 'Holds the next event for the transaction before publishing it.',
    proves: 'Readiness recomputes when the late event lands instead of freezing at a stale score.',
    target: 'transaction',
    targetKey: 'transactionId',
    usesDelay: true,
    usesCount: false,
  },
  OUT_OF_ORDER_EVENT: {
    type: 'OUT_OF_ORDER_EVENT',
    label: 'Out-of-order event',
    family: 'event-stream',
    effect: 'Publishes a later event before an earlier one on the same aggregate.',
    proves: 'State building is order-independent; occurredAt, not arrival, defines the timeline.',
    target: 'transaction',
    targetKey: 'transactionId',
    usesDelay: false,
    usesCount: false,
  },
  DROP_EVENT: {
    type: 'DROP_EVENT',
    label: 'Drop event',
    family: 'event-stream',
    effect: 'Silently discards events before they reach the raw topic.',
    proves: 'The gap becomes a detected MISSING gap rather than an invisible hole in the story.',
    target: 'transaction',
    targetKey: 'transactionId',
    usesDelay: false,
    usesCount: true,
  },
  DELETE_EVIDENCE: {
    type: 'DELETE_EVIDENCE',
    label: 'Delete evidence object',
    family: 'evidence',
    effect: 'Removes the stored object from MinIO while the row survives.',
    proves: 'Integrity verification reports the object missing and the artifact is invalidated.',
    target: 'evidence',
    targetKey: 'evidenceId',
    usesDelay: false,
    usesCount: false,
  },
  CORRUPT_EVIDENCE_HASH: {
    type: 'CORRUPT_EVIDENCE_HASH',
    label: 'Corrupt evidence hash',
    family: 'evidence',
    effect: 'Rewrites the stored object so its sha256 no longer matches the recorded one.',
    proves: 'EvidenceIntegrityService catches the mismatch; the artifact stops satisfying anything.',
    target: 'evidence',
    targetKey: 'evidenceId',
    usesDelay: false,
    usesCount: false,
  },
  EXPIRE_EVIDENCE: {
    type: 'EXPIRE_EVIDENCE',
    label: 'Expire evidence',
    family: 'evidence',
    effect: 'Moves the artifact’s expiry into the past.',
    proves: 'Readiness applies the contract 7 expiry penalty and an EXPIRED gap appears.',
    target: 'evidence',
    targetKey: 'evidenceId',
    usesDelay: false,
    usesCount: false,
  },
  CONFLICTING_EVIDENCE: {
    type: 'CONFLICTING_EVIDENCE',
    label: 'Conflicting evidence',
    family: 'evidence',
    effect: 'Adds an artifact that disagrees with an existing one on a shared field.',
    proves: 'ContradictionDetector finds the conflict and the auto-prepare path closes.',
    target: 'transaction',
    targetKey: 'transactionId',
    usesDelay: false,
    usesCount: false,
  },
  KILL_WORKER: {
    type: 'KILL_WORKER',
    label: 'Kill worker',
    family: 'infrastructure',
    effect: 'Stops a service mid-stream.',
    proves: 'Consumer lag builds and drains on restart with no lost and no doubled processing.',
    target: 'service',
    targetKey: 'service',
    usesDelay: false,
    usesCount: false,
  },
  RESTART_CONSUMER: {
    type: 'RESTART_CONSUMER',
    label: 'Restart consumer',
    family: 'infrastructure',
    effect: 'Rejoins the consumer group, forcing a rebalance and offset replay.',
    proves: 'Reprocessed offsets are deduped rather than re-applied.',
    target: 'service',
    targetKey: 'service',
    usesDelay: false,
    usesCount: false,
  },
  SLOW_CONSUMER: {
    type: 'SLOW_CONSUMER',
    label: 'Slow consumer',
    family: 'infrastructure',
    effect: 'Adds artificial latency to every poll of a consumer.',
    proves: 'Backpressure shows up as lag, not as dropped work or timeouts cascading upstream.',
    target: 'service',
    targetKey: 'service',
    usesDelay: true,
    usesCount: false,
  },
  REPLAY_EVENTS: {
    type: 'REPLAY_EVENTS',
    label: 'Replay events',
    family: 'event-stream',
    effect: 'Re-emits a topic from an earlier offset.',
    proves: 'A full replay reproduces the same state - the platform is deterministic over its log.',
    target: 'topic',
    targetKey: 'topic',
    usesDelay: false,
    usesCount: false,
  },
  INJECT_DISPUTE: {
    type: 'INJECT_DISPUTE',
    label: 'Inject dispute',
    family: 'dispute',
    effect: 'Raises a dispute against a transaction, opening a real case workflow.',
    proves: 'The whole pipeline runs: assembly, gaps, admission, gate, package.',
    target: 'transaction',
    targetKey: 'transactionId',
    usesDelay: false,
    usesCount: false,
  },
} as const;

/** Catalogue order, grouped by family, with every contract 6 type present exactly once. */
export const CHAOS_FAMILIES: readonly ChaosFamily[] = [
  'event-stream',
  'evidence',
  'infrastructure',
  'dispute',
];

export function chaosOfFamily(family: ChaosFamily): ChaosSpec[] {
  return CHAOS_TYPES.map((type) => CHAOS_CATALOG[type]).filter((spec) => spec.family === family);
}

/**
 * Services a chaos injection can target. Names match contract 2 exactly; the consumer group of
 * each is `pdei-<service-name>` (contract 4).
 */
export const CHAOS_SERVICES: readonly string[] = [
  'normalization-worker',
  'state-builder-worker',
  'readiness-worker',
  'case-orchestrator-service',
  'document-processor-service',
  'audit-service',
  'ingestion-service',
  'api-gateway-service',
];

/** Kafka topics, mirroring `common.kafka.Topics` (SHARED-LIBRARY-API 1.5). */
export const KAFKA_TOPICS: readonly string[] = [
  'pdei.raw.events.v1',
  'pdei.canonical.events.v1',
  'pdei.evidence.events.v1',
  'pdei.readiness.events.v1',
  'pdei.dispute.events.v1',
  'pdei.case.events.v1',
  'pdei.audit.events.v1',
  'pdei.dlq.v1',
];

/**
 * Failure profiles offered by the run launcher.
 *
 * simulator-service takes `failureProfile` as a free string (contract 8.5) and publishes no
 * enumeration, so this list is the console's own vocabulary. If the simulator ever exposes
 * `GET /sim/v1/profiles`, replace it with that.
 */
export const FAILURE_PROFILES: readonly { value: string; label: string; detail: string }[] = [
  { value: 'NONE', label: 'None', detail: 'Clean run. Every event arrives once, in order.' },
  {
    value: 'DUPLICATE_HEAVY',
    label: 'Duplicate heavy',
    detail: 'A share of events is redelivered, exercising the dedupe path continuously.',
  },
  {
    value: 'LATE_ARRIVALS',
    label: 'Late arrivals',
    detail: 'Delivery proofs and communications land after the dispute is raised.',
  },
  {
    value: 'LOSSY',
    label: 'Lossy',
    detail: 'A share of events never arrives, producing genuine MISSING gaps.',
  },
  {
    value: 'DEGRADED_EVIDENCE',
    label: 'Degraded evidence',
    detail: 'Expired, low-quality and unverifiable artifacts appear throughout.',
  },
];
