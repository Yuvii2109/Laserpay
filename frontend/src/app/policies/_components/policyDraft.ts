/**
 * Draft editing for the requirement matrix.
 *
 * A policy version is immutable (contract 8.1: `PUT /policies/{policyId}` publishes a NEW
 * version and closes the previous interval). The console therefore never edits a `PolicyView`;
 * it builds a `PolicyDraft` from one, mutates the draft, and publishes it. Everything in this
 * module is pure so the grid can stay dumb.
 */
import { REQUIREMENT_WEIGHT } from '@/lib/types/readiness';
import type { RequirementStrength } from '@/lib/types/readiness';
import type { EvidenceType } from '@/lib/types/evidence';
import type { PolicyDraft, PolicyView, RequirementSpec } from '@/lib/types/policy';

/** A matrix cell is a strength, or `NONE` when the evidence type is not in the policy at all. */
export type CellValue = RequirementStrength | 'NONE';

export const CELL_VALUES: readonly CellValue[] = [
  'NONE',
  'MANDATORY',
  'RECOMMENDED',
  'OPTIONAL',
  'PROHIBITED',
] as const;

export const CELL_SHORT: Readonly<Record<CellValue, string>> = {
  NONE: '-',
  MANDATORY: 'M',
  RECOMMENDED: 'R',
  OPTIONAL: 'O',
  PROHIBITED: 'P',
};

export const CELL_LABEL: Readonly<Record<CellValue, string>> = {
  NONE: 'Not part of this policy',
  MANDATORY: 'Mandatory',
  RECOMMENDED: 'Recommended',
  OPTIONAL: 'Optional',
  PROHIBITED: 'Prohibited',
};

export const CELL_COLOR: Readonly<Record<CellValue, string>> = {
  NONE: 'var(--status-neutral)',
  MANDATORY: 'var(--seq-700)',
  RECOMMENDED: 'var(--seq-550)',
  OPTIONAL: 'var(--seq-400)',
  PROHIBITED: 'var(--status-critical)',
};

/** `PolicyView` -> the editable subset the PUT accepts. */
export function toDraft(policy: PolicyView): PolicyDraft {
  return {
    merchantId: policy.merchantId,
    reasonCode: policy.reasonCode,
    requirements: policy.requirements.map((requirement) => ({ ...requirement })),
    permittedActions: [...policy.permittedActions],
    prohibitedEvidenceTypes: [...policy.prohibitedEvidenceTypes],
    autoPrepareMinConfidence: policy.autoPrepareMinConfidence,
    maxContradictions: policy.maxContradictions,
    minReadinessScoreForAutoPrepare: policy.minReadinessScoreForAutoPrepare,
    humanReviewAboveAmountMinor: policy.humanReviewAboveAmountMinor,
    currency: policy.currency,
    autoSubmitEnabled: policy.autoSubmitEnabled,
    responseWindowDays: policy.responseWindowDays,
    expiringSoonDays: policy.expiringSoonDays,
  };
}

export function cellValue(draft: PolicyDraft, type: EvidenceType): CellValue {
  const requirement = draft.requirements.find((item) => item.type === type);
  return requirement ? requirement.strength : 'NONE';
}

/**
 * Sets one cell.
 *
 * Weight follows the strength's default (contract 7: 3/2/1/0). A merchant policy may override a
 * weight, but the grid deliberately does not expose that - a per-cell weight editor invites
 * scores that nobody can explain afterwards.
 */
export function setCell(
  draft: PolicyDraft,
  type: EvidenceType,
  value: CellValue,
): PolicyDraft {
  const existing = draft.requirements.find((item) => item.type === type);
  let requirements: RequirementSpec[];

  if (value === 'NONE') {
    requirements = draft.requirements.filter((item) => item.type !== type);
  } else if (existing) {
    requirements = draft.requirements.map((item) =>
      item.type === type
        ? {
            ...item,
            strength: value,
            weight: REQUIREMENT_WEIGHT[value],
            provenanceRequired: value === 'MANDATORY' ? true : item.provenanceRequired,
          }
        : item,
    );
  } else {
    requirements = [
      ...draft.requirements,
      {
        type,
        strength: value,
        weight: REQUIREMENT_WEIGHT[value],
        maxAgeDays: null,
        provenanceRequired: value === 'MANDATORY',
        minQualityScore: 0,
        note: null,
      },
    ];
  }

  return {
    ...draft,
    requirements,
    // The prohibited list and the matrix are two views of one fact; keep them in step.
    prohibitedEvidenceTypes: requirements
      .filter((item) => item.strength === 'PROHIBITED')
      .map((item) => item.type),
  };
}

/** Stable serialisation used for dirty checking - requirement order must not matter. */
function canonical(draft: PolicyDraft): string {
  return JSON.stringify({
    ...draft,
    requirements: [...draft.requirements]
      .map((item) => ({ ...item }))
      .sort((a, b) => a.type.localeCompare(b.type)),
    permittedActions: [...draft.permittedActions].sort(),
    prohibitedEvidenceTypes: [...draft.prohibitedEvidenceTypes].sort(),
  });
}

export function isDirty(policy: PolicyView, draft: PolicyDraft): boolean {
  return canonical(toDraft(policy)) !== canonical(draft);
}

/** Human summary of what changed, for the publish confirmation. */
export function describeChanges(policy: PolicyView, draft: PolicyDraft): string[] {
  const changes: string[] = [];
  const before = new Map(policy.requirements.map((item) => [item.type, item.strength]));
  const after = new Map(draft.requirements.map((item) => [item.type, item.strength]));

  for (const [type, strength] of after) {
    const previous = before.get(type);
    if (previous === undefined) changes.push(`+ ${type} → ${strength}`);
    else if (previous !== strength) changes.push(`~ ${type}: ${previous} → ${strength}`);
  }
  for (const [type] of before) {
    if (!after.has(type)) changes.push(`- ${type} removed`);
  }

  if (policy.autoPrepareMinConfidence !== draft.autoPrepareMinConfidence) {
    changes.push(
      `~ autoPrepareMinConfidence: ${policy.autoPrepareMinConfidence} → ${draft.autoPrepareMinConfidence}`,
    );
  }
  if (policy.maxContradictions !== draft.maxContradictions) {
    changes.push(`~ maxContradictions: ${policy.maxContradictions} → ${draft.maxContradictions}`);
  }
  if (policy.minReadinessScoreForAutoPrepare !== draft.minReadinessScoreForAutoPrepare) {
    changes.push(
      `~ minReadinessScoreForAutoPrepare: ${policy.minReadinessScoreForAutoPrepare} → ${draft.minReadinessScoreForAutoPrepare}`,
    );
  }
  if (policy.humanReviewAboveAmountMinor !== draft.humanReviewAboveAmountMinor) {
    changes.push(
      `~ humanReviewAboveAmountMinor: ${policy.humanReviewAboveAmountMinor} → ${draft.humanReviewAboveAmountMinor} (minor units, ${draft.currency})`,
    );
  }
  if (policy.autoSubmitEnabled !== draft.autoSubmitEnabled) {
    changes.push(`~ autoSubmitEnabled: ${policy.autoSubmitEnabled} → ${draft.autoSubmitEnabled}`);
  }
  if (policy.responseWindowDays !== draft.responseWindowDays) {
    changes.push(`~ responseWindowDays: ${policy.responseWindowDays} → ${draft.responseWindowDays}`);
  }
  if (policy.expiringSoonDays !== draft.expiringSoonDays) {
    changes.push(`~ expiringSoonDays: ${policy.expiringSoonDays} → ${draft.expiringSoonDays}`);
  }

  return changes;
}

/** Label for a policy row: its reason code, or the merchant's baseline profile. */
export function policyLabel(policy: PolicyView): string {
  return policy.reasonCode ?? 'Baseline (no reason code)';
}
