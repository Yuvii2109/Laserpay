/**
 * Simulator + chaos types - contract 6 (`ChaosType`) and 8.5 (`simulator-service`).
 * Mirrors `SimulationRunEntity` / `ChaosInjectionEntity` in platform-persistence.
 */
import type { Iso8601 } from './common';
import type { GapType, ReadinessBand } from './readiness';

export type ChaosType =
  | 'DUPLICATE_EVENT'
  | 'DELAYED_EVENT'
  | 'OUT_OF_ORDER_EVENT'
  | 'DROP_EVENT'
  | 'DELETE_EVIDENCE'
  | 'CORRUPT_EVIDENCE_HASH'
  | 'EXPIRE_EVIDENCE'
  | 'CONFLICTING_EVIDENCE'
  | 'KILL_WORKER'
  | 'RESTART_CONSUMER'
  | 'REPLAY_EVENTS'
  | 'INJECT_DISPUTE'
  | 'SLOW_CONSUMER';

export const CHAOS_TYPES: readonly ChaosType[] = [
  'DUPLICATE_EVENT',
  'DELAYED_EVENT',
  'OUT_OF_ORDER_EVENT',
  'DROP_EVENT',
  'DELETE_EVIDENCE',
  'CORRUPT_EVIDENCE_HASH',
  'EXPIRE_EVIDENCE',
  'CONFLICTING_EVIDENCE',
  'KILL_WORKER',
  'RESTART_CONSUMER',
  'REPLAY_EVENTS',
  'INJECT_DISPUTE',
  'SLOW_CONSUMER',
] as const;

/** Lifecycle of a simulation run (`SimulationRunEntity.status`). */
export type SimulationStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'FAILED';

export const SIMULATION_STATUSES: readonly SimulationStatus[] = [
  'PENDING',
  'RUNNING',
  'COMPLETED',
  'STOPPED',
  'FAILED',
] as const;

/** Lifecycle of one chaos injection (`ChaosInjectionEntity.status`). */
export type ChaosStatus = 'REQUESTED' | 'APPLIED' | 'FAILED' | 'CANCELLED';

export const CHAOS_STATUSES: readonly ChaosStatus[] = [
  'REQUESTED',
  'APPLIED',
  'FAILED',
  'CANCELLED',
] as const;

/** Body of `POST /sim/v1/runs` - contract 8.5. `seed` makes the run reproducible. */
export interface SimulationRunRequest {
  seed: number;
  merchants: number;
  transactions: number;
  days: number;
  /**
   * Dispute rate in **basis points** (contract 8.5): 200 = 2%, 10000 = 100%.
   * Not a [0,1] fraction - this field was typed as one, the server declares it an
   * Integer, and Jackson truncated 0.03 to 0, so every run launched from this console
   * silently produced zero disputes.
   */
  disputeRateBps: number;
  failureProfile?: string;
  scenarioKey?: string;
  requestedBy?: string;
}

/** `GET /sim/v1/runs/{runId}` - progress plus counters. */
export interface SimulationRun {
  runId: string;
  seed: number;
  /**
   * The run's inputs, named exactly as `SimulationRunRequest` names them (contract 8.5).
   * These were once `merchantCount`/`transactionCount` here and `merchants`/`transactions` on
   * the wire, so the runs table rendered "undefined m - undefined tx" for every row.
   */
  merchants: number;
  transactions: number;
  days: number;
  /** Dispute rate in basis points (integer), as stored. */
  disputeRateBps: number;
  failureProfile: string | null;
  scenarioKey: string | null;
  status: SimulationStatus;
  progressPercent: number;
  /** Total the plan intends to emit; `eventsEmitted` climbs toward it while RUNNING. */
  eventsPlanned: number;
  eventsEmitted: number;
  transactionsCreated: number;
  evidenceCreated: number;
  disputesCreated: number;
  createdAt: Iso8601;
  startedAt: Iso8601 | null;
  finishedAt: Iso8601 | null;
  requestedBy: string | null;
  errorMessage: string | null;
  params: Record<string, unknown>;
}

/** Body of `POST /sim/v1/chaos` - contract 8.5. */
export interface ChaosRequest {
  type: ChaosType;
  /** Free-form target selector, e.g. `{ transactionId, evidenceId, service }`. */
  target: Record<string, unknown>;
  delayMs?: number;
  count?: number;
  merchantId?: string;
  runId?: string;
  actor?: string;
}

/** `GET /sim/v1/chaos` row. */
export interface ChaosInjection {
  injectionId: string;
  runId: string | null;
  merchantId: string | null;
  type: ChaosType;
  status: ChaosStatus;
  target: Record<string, unknown>;
  delayMs: number | null;
  eventCount: number | null;
  actor: string | null;
  injectedAt: Iso8601;
  completedAt: Iso8601 | null;
  result: Record<string, unknown>;
  errorMessage: string | null;
}

/** Body of `POST /sim/v1/replay` - contract 8.5. */
export interface ReplayRequest {
  topic: string;
  fromOffset?: number;
  fromTimestamp?: Iso8601;
  merchantId?: string;
}

export interface ReplayResult {
  replayId: string;
  topic: string;
  requestedFrom: string;
  eventsReplayed: number;
  startedAt: Iso8601;
}

/**
 * What a scenario claims it will produce (contract 8.5).
 *
 * This is an assertion target, not decoration: the scenario states its band, gap types and AI
 * path up front, so a run either reproduces its own description or visibly does not.
 */
export interface ScenarioExpectation {
  readinessBand: ReadinessBand;
  scoreMin: number;
  scoreMax: number;
  gapTypes: GapType[];
  /** `DETERMINISTIC` means the case short-circuits without ever reaching a model. */
  aiPath: string;
  classification: string;
  recommendedAction: string;
}

/**
 * `GET /sim/v1/scenarios` - curated demo scenarios.
 *
 * There is no `chaosTypes`/`expectedOutcome`/`estimatedSeconds`: those three lived only in this
 * app's mock router, which predates the simulator. Reading `chaosTypes` off a real response threw
 * and took the whole console down. Chaos is injected through `POST /chaos`, not through scenarios.
 */
export interface Scenario {
  key: string;
  title: string;
  description: string;
  reasonCode: string;
  seed: number;
  merchants: number;
  transactions: number;
  days: number;
  startAt: Iso8601;
  expected: ScenarioExpectation;
  /** One line on what makes this scenario worth watching. */
  demoNote: string;
}
