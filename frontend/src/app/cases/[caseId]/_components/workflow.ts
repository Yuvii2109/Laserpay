/**
 * `DisputeCaseWorkflow` progress model - contract 10.
 *
 * The twelve steps are the Temporal workflow's own activities, in order, with the signals and
 * timers that park between them. `CaseStatus` is the projection the gateway exposes, so this
 * module maps a status onto the step the workflow is sitting on. It is a *presentation* of the
 * workflow, not a second implementation of it: nothing here decides anything.
 *
 * A real `getProgress` query exists on the workflow (contract 10) but is not exposed through
 * the gateway; when it is, replace `stepStateFor` with the workflow's own answer.
 */
import type { CaseStatus } from '@/lib/types/case';

export type WorkflowStepKind = 'activity' | 'signal' | 'timer';

export interface WorkflowStep {
  /** 1-based ordinal exactly as contract 10 numbers them. */
  ordinal: number;
  id: string;
  label: string;
  kind: WorkflowStepKind;
  detail: string;
}

/** Contract 10, verbatim in name and order. */
export const WORKFLOW_STEPS: readonly WorkflowStep[] = [
  {
    ordinal: 1,
    id: 'openCase',
    label: 'openCase',
    kind: 'activity',
    detail: 'Creates the case row and emits CaseOpened on pdei.case.events.v1.',
  },
  {
    ordinal: 2,
    id: 'gatherEvidence',
    label: 'gatherEvidence',
    kind: 'activity',
    detail: 'Idempotent and retryable: links every artifact already known for the transaction.',
  },
  {
    ordinal: 3,
    id: 'detectGaps',
    label: 'detectGaps',
    kind: 'activity',
    detail: 'Runs GapDetector and ContradictionDetector against the requirement set.',
  },
  {
    ordinal: 4,
    id: 'awaitMissingEvidence',
    label: 'awaitMissingEvidence',
    kind: 'timer',
    detail: 'Timer plus the evidenceArrived signal. Waits at most 7 days.',
  },
  {
    ordinal: 5,
    id: 'runAdmissionControl',
    label: 'runAdmissionControl',
    kind: 'activity',
    detail:
      'Contract 9.4 priority scoring plus the Redis budget. Deterministic short-circuits bypass the model here.',
  },
  {
    ordinal: 6,
    id: 'investigate',
    label: 'investigate',
    kind: 'activity',
    detail: 'Calls ai-reasoning-service. Skipped entirely when admission control short-circuits.',
  },
  {
    ordinal: 7,
    id: 'validateAndGate',
    label: 'validateAndGate',
    kind: 'activity',
    detail: 'AiResultValidator (contract 9.3) plus policy. Produces the SafetyVerdict.',
  },
  {
    ordinal: 8,
    id: 'awaitHumanApproval',
    label: 'awaitHumanApproval',
    kind: 'signal',
    detail: 'Parks on the humanDecision signal. On timeout the case escalates.',
  },
  {
    ordinal: 9,
    id: 'prepareRepresentmentPackage',
    label: 'prepareRepresentmentPackage',
    kind: 'activity',
    detail: 'Assembles the MinIO bundle and its manifest under pdei-packages.',
  },
  {
    ordinal: 10,
    id: 'submitRepresentment',
    label: 'submitRepresentment',
    kind: 'activity',
    detail: 'Submits the package to the network and records the submission.',
  },
  {
    ordinal: 11,
    id: 'followUp',
    label: 'followUp',
    kind: 'timer',
    detail: 'Timer loop until the DisputeClosed signal or the deadline passes.',
  },
  {
    ordinal: 12,
    id: 'closeCase',
    label: 'closeCase',
    kind: 'activity',
    detail: 'Terminal activity: records the outcome and emits CaseClosed.',
  },
] as const;

/**
 * The step a case is sitting on, per status. `CLOSED` is past every step; `FAILED` has no
 * defined position, so the caller renders the pipeline as broken rather than as progressed.
 */
const STATUS_TO_ORDINAL: Readonly<Record<CaseStatus, number>> = {
  CREATED: 1,
  ASSEMBLING: 2,
  AWAITING_EVIDENCE: 4,
  INVESTIGATING: 6,
  AWAITING_APPROVAL: 8,
  PREPARED: 9,
  SUBMITTED: 10,
  CLOSED: 12,
  FAILED: 0,
};

export type StepState = 'done' | 'active' | 'pending' | 'skipped' | 'failed' | 'unknown';

export interface StepProgressInput {
  status: CaseStatus;
  /** True when admission control resolved the case without calling the model. */
  bypassedAi: boolean;
}

export function currentOrdinal(status: CaseStatus): number {
  return STATUS_TO_ORDINAL[status] ?? 0;
}

/** State of one step for a case in `status`. */
export function stepStateFor(step: WorkflowStep, input: StepProgressInput): StepState {
  const current = currentOrdinal(input.status);

  if (input.status === 'FAILED') {
    return step.ordinal === 1 ? 'failed' : 'unknown';
  }

  // Contract 10 step 6 is explicitly "may be skipped"; contract 9.4 says when.
  if (input.bypassedAi && step.id === 'investigate') return 'skipped';

  if (step.ordinal < current) return 'done';
  if (step.ordinal === current) return input.status === 'CLOSED' ? 'done' : 'active';
  return 'pending';
}

export const STEP_STATE_LABEL: Readonly<Record<StepState, string>> = {
  done: 'Done',
  active: 'Running',
  pending: 'Not reached',
  skipped: 'Skipped',
  failed: 'Failed',
  unknown: 'Unknown',
};

/** Fraction of the pipeline completed, for the progress bar. */
export function progressFraction(status: CaseStatus): number {
  const current = currentOrdinal(status);
  if (current <= 0) return 0;
  return Math.min(1, Math.max(0, current / WORKFLOW_STEPS.length));
}
