'use client';

import * as React from 'react';
import Link from 'next/link';
import { ShieldCheck } from 'lucide-react';
import { cn } from '@/lib/utils';
import { EmptyState } from './EmptyState';
import { StatusBadge } from './StatusBadge';
import { TimestampDisplay } from './TimestampDisplay';
import { EvidenceTypeIcon, EVIDENCE_TYPE_LABEL } from './EvidenceTypeIcon';
import { SEVERITY_RANK, toneColorVar, type Tone } from '@/lib/format/score';
import { humanizeEnum } from '@/lib/format/id';
import type { GapType, ReadinessGap } from '@/lib/types/readiness';

export interface GapListProps {
  gaps: ReadinessGap[] | undefined;
  className?: string;
  /** Link each row to its transaction (feeds that span transactions). */
  showTransaction?: boolean;
  emptyTitle?: string;
  emptyDescription?: React.ReactNode;
  /** Cap the rows rendered; the rest are summarised in the footer. */
  limit?: number;
}

/** What each gap type means for a representment, in one line an operator can act on. */
export const GAP_TYPE_EXPLANATION: Readonly<Record<GapType, string>> = {
  MISSING: 'A required evidence type has no usable artifact on this transaction.',
  EXPIRED: 'The artifact exists but is past its policy max age, so it no longer satisfies the requirement.',
  EXPIRING_SOON: 'The artifact expires inside the policy horizon; capture a fresh version before a dispute lands.',
  CONTRADICTORY: 'Two artifacts disagree on a field. Contradictions block automatic preparation.',
  UNVERIFIABLE_PROVENANCE: 'The artifact cannot be traced to a source event, so its origin is unverifiable.',
  LOW_QUALITY: 'Extraction quality is below the policy floor; the artifact may not survive scrutiny.',
  VERSION_CONFLICT: 'Two versions of the same artifact are both in play; the version chain needs resolving.',
};

const GAP_TYPE_TONE: Readonly<Record<GapType, Tone>> = {
  MISSING: 'critical',
  EXPIRED: 'serious',
  EXPIRING_SOON: 'warning',
  CONTRADICTORY: 'critical',
  UNVERIFIABLE_PROVENANCE: 'serious',
  LOW_QUALITY: 'warning',
  VERSION_CONFLICT: 'warning',
};

/**
 * A severity-ordered list of readiness gaps.
 *
 * The gaps are produced by `GapDetector` in `evidence-core` and reach the UI on
 * `GET /gaps` or inside a `ReadinessSnapshot`. Nothing is re-detected here: the list orders
 * what the engine found (worst first) and explains what each type costs, because "MISSING
 * DELIVERY_PROOF" only means something if the reader knows it is a mandatory requirement.
 */
export function GapList({
  gaps,
  className,
  showTransaction = false,
  emptyTitle = 'No open gaps',
  emptyDescription = 'Every requirement in the applicable policy is satisfied by a usable artifact, and no contradiction was detected.',
  limit,
}: GapListProps) {
  const ordered = React.useMemo(() => {
    if (!gaps) return [];
    return [...gaps].sort(
      (a, b) =>
        SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] ||
        b.detectedAt.localeCompare(a.detectedAt),
    );
  }, [gaps]);

  if (ordered.length === 0) {
    return (
      <EmptyState
        icon={ShieldCheck}
        title={emptyTitle}
        description={emptyDescription}
        className={className}
        compact
      />
    );
  }

  const rows = limit ? ordered.slice(0, limit) : ordered;
  const hidden = ordered.length - rows.length;

  return (
    <div className={cn('space-y-2', className)}>
      <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
        {rows.map((gap) => {
          const tone = GAP_TYPE_TONE[gap.type] ?? 'neutral';
          return (
            <li key={gap.gapId} className="flex flex-wrap items-start gap-x-3 gap-y-1.5 p-3">
              <span
                className="mt-1 h-8 w-1 shrink-0 rounded-full"
                style={{ backgroundColor: toneColorVar(tone) }}
                aria-hidden
              />

              <div className="min-w-0 flex-1 space-y-1">
                <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                  <span className="text-sm font-medium text-foreground">
                    {humanizeEnum(gap.type)}
                  </span>
                  {gap.evidenceType ? (
                    <span className="inline-flex items-center gap-1.5 text-sm text-muted-foreground">
                      <EvidenceTypeIcon type={gap.evidenceType} className="size-3.5" />
                      {EVIDENCE_TYPE_LABEL[gap.evidenceType] ?? humanizeEnum(gap.evidenceType)}
                    </span>
                  ) : null}
                  <StatusBadge kind="severity" value={gap.severity} />
                </div>

                <p className="text-sm text-muted-foreground">
                  {gap.detail ?? GAP_TYPE_EXPLANATION[gap.type]}
                </p>

                <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                  <span>
                    detected <TimestampDisplay value={gap.detectedAt} />
                  </span>
                  {gap.expiresAt ? (
                    <span>
                      expires <TimestampDisplay value={gap.expiresAt} />
                    </span>
                  ) : null}
                  {gap.evidenceId ? (
                    <Link
                      href={`/evidence/${gap.evidenceId}`}
                      className="mono-id underline-offset-4 hover:text-foreground hover:underline"
                    >
                      {gap.evidenceId}
                    </Link>
                  ) : null}
                  {showTransaction ? (
                    <Link
                      href={`/transactions/${gap.transactionId}`}
                      className="mono-id underline-offset-4 hover:text-foreground hover:underline"
                    >
                      {gap.transactionId}
                    </Link>
                  ) : null}
                </div>
              </div>
            </li>
          );
        })}
      </ul>

      {hidden > 0 ? (
        <p className="text-xs text-muted-foreground">
          {hidden} further gap{hidden === 1 ? '' : 's'} not shown.
        </p>
      ) : null}
    </div>
  );
}
