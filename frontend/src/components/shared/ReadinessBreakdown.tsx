'use client';

import * as React from 'react';
import Link from 'next/link';
import { Check, Info, Minus, TriangleAlert, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { ReadinessMeter } from './ReadinessMeter';
import { EvidenceTypeIcon, EVIDENCE_TYPE_LABEL } from './EvidenceTypeIcon';
import { TimestampDisplay } from './TimestampDisplay';
import { humanizeEnum } from '@/lib/format/id';
import { formatScore } from '@/lib/format/score';
import type {
  ReadinessGap,
  ReadinessSnapshot,
  RequirementStrength,
  RequirementView,
} from '@/lib/types/readiness';

export interface ReadinessBreakdownProps {
  snapshot: ReadinessSnapshot;
  className?: string;
}

/**
 * Contract 7 penalty rules, as data.
 *
 * These are the ENGINE's rules, restated here only to itemise a total the engine already
 * produced. The console never scores anything: `snapshot.baseScore`, `snapshot.penaltyPoints`
 * and `snapshot.score` are authoritative, and if this itemisation cannot reproduce them the
 * component says so instead of quietly showing its own arithmetic.
 */
const PENALTY_RULES = [
  {
    id: 'CONTRADICTORY',
    label: 'Contradictory evidence',
    points: 15,
    per: 'each' as const,
    detail: '−15 per CONTRADICTORY gap',
    mandatoryOnly: false,
  },
  {
    id: 'EXPIRED',
    label: 'Expired mandatory evidence',
    points: 10,
    per: 'each' as const,
    detail: '−10 per EXPIRED mandatory evidence',
    mandatoryOnly: true,
  },
  {
    id: 'EXPIRING_SOON',
    label: 'Mandatory evidence expiring within 7 days',
    points: 5,
    per: 'each' as const,
    detail: '−5 per EXPIRING_SOON mandatory evidence',
    mandatoryOnly: true,
  },
  {
    id: 'UNVERIFIABLE_PROVENANCE',
    label: 'Unverifiable provenance on mandatory evidence',
    points: 20,
    per: 'once' as const,
    detail: '−20 if any mandatory evidence has unverifiable provenance',
    mandatoryOnly: true,
  },
] as const;

const STRENGTH_ORDER: Readonly<Record<RequirementStrength, number>> = {
  MANDATORY: 0,
  RECOMMENDED: 1,
  OPTIONAL: 2,
  PROHIBITED: 3,
};

interface PenaltyLine {
  ruleId: string;
  label: string;
  detail: string;
  count: number;
  points: number;
  gaps: ReadinessGap[];
}

/** Sum of requirement weights, with RECOMMENDED counted at half - contract 7's base formula. */
function weighted(requirements: RequirementView[], predicate: (item: RequirementView) => boolean) {
  let total = 0;
  for (const requirement of requirements) {
    if (!predicate(requirement)) continue;
    if (requirement.strength === 'MANDATORY') total += requirement.weight;
    else if (requirement.strength === 'RECOMMENDED') total += requirement.weight / 2;
  }
  return total;
}

function itemisePenalties(snapshot: ReadinessSnapshot): PenaltyLine[] {
  const mandatoryTypes = new Set(
    snapshot.requirements
      .filter((requirement) => requirement.strength === 'MANDATORY')
      .map((requirement) => requirement.type),
  );

  return PENALTY_RULES.map((rule) => {
    const matching = snapshot.gaps.filter((gap) => {
      if (gap.type !== rule.id) return false;
      if (!rule.mandatoryOnly) return true;
      return gap.evidenceType !== null && mandatoryTypes.has(gap.evidenceType);
    });
    const count = matching.length;
    const points = rule.per === 'once' ? (count > 0 ? rule.points : 0) : count * rule.points;
    return {
      ruleId: rule.id,
      label: rule.label,
      detail: rule.detail,
      count,
      points,
      gaps: matching,
    };
  });
}

/**
 * The readiness score, taken apart.
 *
 * Contract 7's formula is deterministic, which means it is explainable - and a score a merchant
 * cannot explain is a score they will not act on. This panel shows the base-score arithmetic
 * (which requirements counted, at what weight), then every penalty the engine applied, then the
 * clamp. Everything displayed comes out of the snapshot; the only thing computed here is the
 * attribution of the engine's own penalty total to individual gaps.
 */
export function ReadinessBreakdown({ snapshot, className }: ReadinessBreakdownProps) {
  const requirements = React.useMemo(
    () =>
      [...snapshot.requirements].sort(
        (a, b) =>
          STRENGTH_ORDER[a.strength] - STRENGTH_ORDER[b.strength] ||
          Number(a.satisfied) - Number(b.satisfied) ||
          a.type.localeCompare(b.type),
      ),
    [snapshot.requirements],
  );

  const penalties = React.useMemo(() => itemisePenalties(snapshot), [snapshot]);
  const itemisedTotal = penalties.reduce((total, line) => total + line.points, 0);
  const reconciles = itemisedTotal === Math.round(snapshot.penaltyPoints);

  const satisfiedWeight = weighted(requirements, (item) => item.satisfied);
  const totalWeight = weighted(requirements, () => true);
  const mandatoryCount = requirements.filter((item) => item.strength === 'MANDATORY').length;
  const mandatorySatisfied = requirements.filter(
    (item) => item.strength === 'MANDATORY' && item.satisfied,
  ).length;

  return (
    <div className={cn('space-y-5', className)}>
      {/* ---- the arithmetic, top to bottom ---- */}
      <Card className="p-5">
        <div className="grid gap-6 lg:grid-cols-[minmax(0,18rem)_1fr]">
          <div className="space-y-3">
            <ReadinessMeter
              score={snapshot.score}
              band={snapshot.band}
              variant="hero"
              caption={undefined}
            />
            <dl className="space-y-1 text-xs text-muted-foreground">
              <div className="flex justify-between gap-3">
                <dt>Computed</dt>
                <dd>
                  <TimestampDisplay value={snapshot.computedAt} />
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt>Reason code</dt>
                <dd className="text-foreground">
                  {snapshot.reasonCode ? humanizeEnum(snapshot.reasonCode) : 'Baseline profile'}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt>Policy version</dt>
                <dd className="mono-id text-foreground">{snapshot.policyVersionId ?? '-'}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt>Snapshot</dt>
                <dd className="mono-id text-foreground">{snapshot.snapshotId}</dd>
              </div>
            </dl>
          </div>

          <div className="space-y-3">
            <h3 className="text-sm font-semibold tracking-tight">How this score was reached</h3>

            <ol className="space-y-2.5 text-sm">
              <li className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 border-b border-border pb-2.5">
                <span className="min-w-0">
                  <span className="font-medium text-foreground">Base score</span>
                  <span className="ml-2 text-muted-foreground">
                    100 × ({formatWeight(satisfiedWeight)} satisfied weight ÷{' '}
                    {formatWeight(totalWeight)} total weight)
                  </span>
                  <span className="mt-0.5 block text-xs text-muted-foreground">
                    Mandatory counts at full weight, recommended at half; optional and prohibited
                    do not enter the ratio.
                  </span>
                </span>
                <span className="tabular text-base font-semibold text-foreground">
                  {formatScore(snapshot.baseScore)}
                </span>
              </li>

              {penalties.map((line) => (
                <li
                  key={line.ruleId}
                  className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 border-b border-border pb-2.5 last:border-0"
                >
                  <span className="min-w-0">
                    <span
                      className={cn(
                        'font-medium',
                        line.points > 0 ? 'text-foreground' : 'text-muted-foreground',
                      )}
                    >
                      {line.label}
                    </span>
                    <span className="ml-2 text-muted-foreground">
                      {line.count} matched · {line.detail}
                    </span>
                  </span>
                  <span
                    className="tabular text-base font-semibold"
                    style={{ color: line.points > 0 ? 'var(--status-critical)' : undefined }}
                  >
                    {line.points > 0 ? `−${line.points}` : '0'}
                  </span>
                </li>
              ))}

              <li className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 pt-1">
                <span>
                  <span className="font-medium text-foreground">
                    Final score = clamp(base − penalties, 0, 100)
                  </span>
                  <span className="ml-2 text-muted-foreground">
                    {formatScore(snapshot.baseScore)} − {Math.round(snapshot.penaltyPoints)}
                  </span>
                </span>
                <span className="tabular text-lg font-semibold text-foreground">
                  {formatScore(snapshot.score)}
                </span>
              </li>
            </ol>

            {!reconciles ? (
              <Alert variant="warning">
                <TriangleAlert className="size-4" style={{ color: 'var(--status-warning)' }} />
                <AlertTitle>Itemisation does not add up to the engine total</AlertTitle>
                <AlertDescription>
                  This view attributed {itemisedTotal} penalty points to individual gaps, but the
                  engine recorded {Math.round(snapshot.penaltyPoints)}. The engine is
                  authoritative - the difference means a penalty was applied for something not
                  present in this snapshot&apos;s gap list.
                </AlertDescription>
              </Alert>
            ) : null}
          </div>
        </div>
      </Card>

      {/* ---- requirement matrix ---- */}
      <Card className="overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-5 py-3">
          <h3 className="text-sm font-semibold tracking-tight">Requirements</h3>
          <span className="text-xs text-muted-foreground">
            {mandatorySatisfied} of {mandatoryCount} mandatory satisfied · {requirements.length}{' '}
            requirement{requirements.length === 1 ? '' : 's'} in the applicable policy
          </span>
        </div>

        <div className="overflow-x-auto scrollbar-thin">
          <table className="w-full text-sm">
            <caption className="sr-only">
              Every requirement in the applicable policy, its strength, its scoring weight,
              whether it is satisfied, and the evidence that satisfies it.
            </caption>
            <thead>
              <tr className="border-b border-border text-xs text-muted-foreground">
                <th scope="col" className="px-5 py-2 text-left font-medium">Evidence type</th>
                <th scope="col" className="px-3 py-2 text-left font-medium">Strength</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Weight</th>
                <th scope="col" className="px-3 py-2 text-left font-medium">Satisfied</th>
                <th scope="col" className="px-5 py-2 text-left font-medium">Satisfied by</th>
              </tr>
            </thead>
            <tbody>
              {requirements.map((requirement) => (
                <tr key={requirement.type} className="border-b border-border last:border-0">
                  <th scope="row" className="px-5 py-2 text-left font-normal">
                    <span className="inline-flex items-center gap-2">
                      <EvidenceTypeIcon type={requirement.type} />
                      <span className="text-foreground">
                        {EVIDENCE_TYPE_LABEL[requirement.type] ?? humanizeEnum(requirement.type)}
                      </span>
                    </span>
                    {requirement.note ? (
                      <span className="mt-0.5 block text-xs text-muted-foreground">
                        {requirement.note}
                      </span>
                    ) : null}
                  </th>
                  <td className="px-3 py-2">
                    <StrengthBadge strength={requirement.strength} />
                  </td>
                  <td className="tabular px-3 py-2 text-right text-muted-foreground">
                    {requirement.weight}
                  </td>
                  <td className="px-3 py-2">
                    <SatisfiedMark
                      satisfied={requirement.satisfied}
                      strength={requirement.strength}
                    />
                  </td>
                  <td className="px-5 py-2">
                    {requirement.satisfyingEvidenceIds.length === 0 ? (
                      <span className="text-xs text-muted-foreground">-</span>
                    ) : (
                      <span className="flex flex-wrap gap-x-2 gap-y-1">
                        {requirement.satisfyingEvidenceIds.map((evidenceId) => (
                          <Link
                            key={evidenceId}
                            href={`/evidence/${evidenceId}`}
                            className="mono-id text-xs underline-offset-4 hover:underline"
                          >
                            {evidenceId}
                          </Link>
                        ))}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {requirements.length === 0 ? (
          <p className="px-5 py-6 text-sm text-muted-foreground">
            The applicable policy declares no requirements for this reason code.
          </p>
        ) : null}
      </Card>

      <p className="flex items-start gap-2 text-xs text-muted-foreground">
        <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden />
        Scoring happens in <span className="mono-id">evidence-core</span>&apos;s{' '}
        <span className="mono-id">ReadinessEngine</span> and is fully deterministic: the same
        evidence and the same policy version always produce the same score. This console displays
        that result and never recomputes it.
      </p>
    </div>
  );
}

function formatWeight(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

function StrengthBadge({ strength }: { strength: RequirementStrength }) {
  const variant = strength === 'MANDATORY' ? 'primary' : strength === 'PROHIBITED' ? 'outline' : 'subtle';
  return (
    <Badge variant={variant} className="text-2xs">
      {humanizeEnum(strength)}
    </Badge>
  );
}

function SatisfiedMark({
  satisfied,
  strength,
}: {
  satisfied: boolean;
  strength: RequirementStrength;
}) {
  if (satisfied) {
    return (
      <span
        className="inline-flex items-center gap-1.5 text-xs font-medium"
        style={{ color: 'var(--status-good)' }}
      >
        <Check className="size-3.5" aria-hidden />
        Yes
      </span>
    );
  }
  if (strength === 'MANDATORY') {
    return (
      <span
        className="inline-flex items-center gap-1.5 text-xs font-medium"
        style={{ color: 'var(--status-critical)' }}
      >
        <X className="size-3.5" aria-hidden />
        No
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
      <Minus className="size-3.5" aria-hidden />
      No
    </span>
  );
}
