'use client';

import * as React from 'react';
import { cn } from '@/lib/utils';
import { EVIDENCE_TYPE_LABEL, EvidenceTypeIcon } from '@/components/shared/EvidenceTypeIcon';
import { EVIDENCE_TYPES } from '@/lib/types/evidence';
import type { EvidenceType } from '@/lib/types/evidence';
import type { PolicyDraft, PolicyView } from '@/lib/types/policy';
import {
  CELL_COLOR,
  CELL_LABEL,
  CELL_SHORT,
  CELL_VALUES,
  cellValue,
  policyLabel,
  type CellValue,
} from './policyDraft';

export interface RequirementMatrixProps {
  /** One row per policy the merchant owns, in display order. */
  policies: readonly PolicyView[];
  drafts: Readonly<Record<string, PolicyDraft>>;
  dirtyPolicyIds: ReadonlySet<string>;
  onCellChange: (policyId: string, type: EvidenceType, value: CellValue) => void;
  selectedPolicyId: string | null;
  onSelectPolicy: (policyId: string) => void;
}

/**
 * The reason-code by evidence-type requirement matrix.
 *
 * One row per policy (a merchant has one policy per reason code plus a baseline), one column
 * per `EvidenceType`. A cell is a `RequirementStrength` or nothing at all.
 *
 * The cells are native selects on purpose: 13 columns times the merchant's policies is a lot of
 * controls, and a native select is the only one that stays keyboard-navigable, screen-reader
 * correct and cheap at that count. The letter and the colour are redundant encodings of the
 * same value - the letter is what actually carries it.
 */
export function RequirementMatrix({
  policies,
  drafts,
  dirtyPolicyIds,
  onCellChange,
  selectedPolicyId,
  onSelectPolicy,
}: RequirementMatrixProps) {
  return (
    <div className="space-y-3">
      <div className="overflow-x-auto scrollbar-thin rounded-lg border border-border bg-card">
        <table className="w-full border-collapse text-xs">
          <caption className="sr-only">
            Requirement strength for each evidence type, per dispute reason code
          </caption>
          <thead>
            <tr>
              <th
                scope="col"
                className="sticky left-0 z-20 min-w-[13rem] border-b border-r border-border bg-card p-2 text-left font-medium"
              >
                Reason code
              </th>
              {EVIDENCE_TYPES.map((type) => (
                <th
                  key={type}
                  scope="col"
                  className="border-b border-border p-1.5 align-bottom font-medium"
                  title={EVIDENCE_TYPE_LABEL[type]}
                >
                  <div className="flex h-28 items-end justify-center">
                    <span
                      className="whitespace-nowrap text-2xs text-muted-foreground"
                      style={{ writingMode: 'vertical-rl', transform: 'rotate(180deg)' }}
                    >
                      {EVIDENCE_TYPE_LABEL[type]}
                    </span>
                  </div>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {policies.map((policy) => {
              const draft = drafts[policy.policyId];
              const dirty = dirtyPolicyIds.has(policy.policyId);
              const selected = selectedPolicyId === policy.policyId;
              return (
                <tr
                  key={policy.policyId}
                  className={cn(
                    'border-b border-border last:border-b-0',
                    selected && 'bg-accent/40',
                  )}
                >
                  <th
                    scope="row"
                    className={cn(
                      'sticky left-0 z-10 border-r border-border p-2 text-left font-normal',
                      selected ? 'bg-accent/60' : 'bg-card',
                    )}
                  >
                    <button
                      type="button"
                      onClick={() => onSelectPolicy(policy.policyId)}
                      className="w-full text-left"
                      aria-pressed={selected}
                    >
                      <span className="block truncate font-medium text-foreground">
                        {policyLabel(policy)}
                      </span>
                      <span className="mono-id block text-2xs text-muted-foreground">
                        {policy.policyId} · v{policy.version}
                        {dirty ? ' · edited' : ''}
                      </span>
                    </button>
                  </th>

                  {EVIDENCE_TYPES.map((type) => {
                    const value: CellValue = draft ? cellValue(draft, type) : 'NONE';
                    return (
                      <td key={type} className="p-1 text-center">
                        <label className="sr-only" htmlFor={`cell-${policy.policyId}-${type}`}>
                          {policyLabel(policy)} · {EVIDENCE_TYPE_LABEL[type]}
                        </label>
                        <select
                          id={`cell-${policy.policyId}-${type}`}
                          value={value}
                          disabled={!draft}
                          onChange={(event) =>
                            onCellChange(policy.policyId, type, event.target.value as CellValue)
                          }
                          title={`${policyLabel(policy)} · ${EVIDENCE_TYPE_LABEL[type]}: ${CELL_LABEL[value]}`}
                          className={cn(
                            'h-7 w-9 cursor-pointer appearance-none rounded-md border text-center text-xs font-semibold',
                            'focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-40',
                          )}
                          style={{
                            color: CELL_COLOR[value],
                            borderColor:
                              value === 'NONE'
                                ? 'hsl(var(--border))'
                                : `color-mix(in oklab, ${CELL_COLOR[value]} 40%, transparent)`,
                            backgroundColor:
                              value === 'NONE'
                                ? 'transparent'
                                : `color-mix(in oklab, ${CELL_COLOR[value]} 12%, transparent)`,
                          }}
                        >
                          {CELL_VALUES.map((option) => (
                            <option key={option} value={option}>
                              {CELL_SHORT[option]}
                            </option>
                          ))}
                        </select>
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-2xs text-muted-foreground">
        <span>Legend:</span>
        {CELL_VALUES.map((value) => (
          <span key={value} className="inline-flex items-center gap-1.5">
            <span
              className="inline-flex size-4 items-center justify-center rounded border text-[0.625rem] font-semibold"
              style={{
                color: CELL_COLOR[value],
                borderColor: `color-mix(in oklab, ${CELL_COLOR[value]} 40%, transparent)`,
              }}
              aria-hidden
            >
              {CELL_SHORT[value]}
            </span>
            {CELL_LABEL[value]}
          </span>
        ))}
        <span className="ml-auto inline-flex items-center gap-1.5">
          <EvidenceTypeIcon type="DELIVERY_PROOF" />
          weights: MANDATORY 3 · RECOMMENDED 2 · OPTIONAL 1 · PROHIBITED 0
        </span>
      </div>
    </div>
  );
}
