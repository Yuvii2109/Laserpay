/**
 * Why a case did not go to the model.
 *
 * The X-Ray payload (`core.model.CaseXRay`) carries the investigation result but not the
 * `AdmissionDecision` that produced - or refused - it. When `investigation` is null the console
 * would otherwise have nothing to say, so this module *reconstructs* the contract 9.4
 * short-circuit from the same deterministic inputs the platform used.
 *
 * This is a clearly named fallback, not a second implementation of admission control: it never
 * decides anything, it only explains. Whenever `GET /investigations/{id}` is reachable the UI
 * prefers the platform's own `AdmissionDecision` and this module is not consulted.
 * See "Known gaps" in frontend/context.md.
 */
import { isDeterministicResult } from '@/lib/types/ai';
import type { CaseStatus, CaseXRay } from '@/lib/types/case';
import type { RecommendedAction, ShortCircuit } from '@/lib/types/ai';

export interface BypassExplanation {
  /** Null when the workflow simply has not reached admission control yet. */
  shortCircuit: ShortCircuit | null;
  /** The action contract 9.4 prescribes for that short-circuit. */
  deterministicAction: RecommendedAction | null;
  /** The contract clause, quoted. */
  rule: string;
  /** What this specific case looked like when the rule matched. */
  detail: string;
  /** False when the workflow has not run admission control yet (steps 1-4). */
  reachedAdmission: boolean;
  /** True when the explanation was reconstructed here rather than read from the platform. */
  reconstructed: true;
}

/** Statuses at which the workflow has not yet reached `runAdmissionControl` (contract 10, 5). */
const PRE_ADMISSION_STATUSES: readonly CaseStatus[] = ['CREATED', 'ASSEMBLING', 'AWAITING_EVIDENCE'];

export function deriveBypass(xray: CaseXRay, now: Date = new Date()): BypassExplanation {
  const requirements = xray.readiness?.requirements ?? [];
  const mandatory = requirements.filter((requirement) => requirement.strength === 'MANDATORY');
  const unsatisfiedMandatory = mandatory.filter((requirement) => !requirement.satisfied);
  const contradictionCount = xray.contradictions.length;
  const evidenceCount = xray.evidence.length;
  const deadlinePassed =
    xray.deadlineAt !== null && Date.parse(xray.deadlineAt) <= now.getTime();

  if (PRE_ADMISSION_STATUSES.includes(xray.caseStatus)) {
    return {
      shortCircuit: null,
      deterministicAction: null,
      rule: 'Contract 10, step 5: runAdmissionControl has not executed yet.',
      detail: `The workflow is still at ${xray.caseStatus}. Nothing has been offered to the reasoner.`,
      reachedAdmission: false,
      reconstructed: true,
    };
  }

  if (evidenceCount === 0) {
    return {
      shortCircuit: 'NO_EVIDENCE',
      deterministicAction: 'ACCEPT_LIABILITY',
      rule: 'Contract 9.4: "zero evidence present at all -> ACCEPT_LIABILITY recommendation to human".',
      detail: 'No artifact of any kind is linked to this transaction. There is nothing to reason over.',
      reachedAdmission: true,
      reconstructed: true,
    };
  }

  if (deadlinePassed) {
    return {
      shortCircuit: 'PAST_DEADLINE',
      deterministicAction: 'ESCALATE_TO_HUMAN',
      rule: 'Contract 9.4: "dispute already past deadline -> ESCALATE_TO_HUMAN".',
      detail: `The representment deadline (${xray.deadlineAt}) has passed; a model call cannot change the outcome.`,
      reachedAdmission: true,
      reconstructed: true,
    };
  }

  if (mandatory.length > 0 && unsatisfiedMandatory.length === 0 && contradictionCount === 0) {
    return {
      shortCircuit: 'ALL_REQUIREMENTS_SATISFIED',
      deterministicAction: 'PREPARE_REPRESENTMENT',
      rule: 'Contract 9.4: "all MANDATORY requirements satisfied, zero contradictions -> auto PREPARE_REPRESENTMENT".',
      detail: `${mandatory.length} mandatory requirement${mandatory.length === 1 ? '' : 's'} satisfied and no contradictions detected. The deterministic path resolved the case; no tokens were spent.`,
      reachedAdmission: true,
      reconstructed: true,
    };
  }

  return {
    shortCircuit: 'BELOW_PRIORITY_THRESHOLD',
    deterministicAction: null,
    rule: 'Contract 9.4: admission requires priority >= 55, a token-bucket allowance and an unresolved deterministic path.',
    detail: `No stored investigation exists for this case, and none of the three deterministic short-circuits matches (${unsatisfiedMandatory.length} unsatisfied mandatory requirement(s), ${contradictionCount} contradiction(s)). The most likely cause is that the priority score fell below the admission threshold or the daily AI budget was exhausted.`,
    reachedAdmission: true,
    reconstructed: true,
  };
}

/** True when the case reached a conclusion without a model call. */
export function isBypassed(xray: CaseXRay): boolean {
  if (!xray.investigation) return true;
  return isDeterministicResult(xray.investigation.modelMetadata);
}
