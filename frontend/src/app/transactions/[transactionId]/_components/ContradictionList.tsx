'use client';

import Link from 'next/link';
import { GitCompareArrows } from 'lucide-react';
import { EmptyState, StatusBadge, TimestampDisplay } from '@/components/shared';
import type { ContradictionView } from '@/lib/types/readiness';

export interface ContradictionListProps {
  contradictions: ContradictionView[] | undefined;
}

/**
 * Cross-evidence field conflicts found by `ContradictionDetector`.
 *
 * They are shown as a two-sided comparison rather than a sentence, because the decision an
 * operator has to make is "which of these two artifacts is wrong" — and contract 9.3 rule 5
 * means a single unresolved contradiction can block automatic preparation outright.
 */
export function ContradictionList({ contradictions }: ContradictionListProps) {
  if (!contradictions || contradictions.length === 0) {
    return (
      <EmptyState
        icon={GitCompareArrows}
        title="No contradictions"
        description="No two artifacts on this transaction disagree on a compared field. Contradictions cost 15 readiness points each and block auto-preparation."
        compact
      />
    );
  }

  return (
    <ul className="space-y-3">
      {contradictions.map((contradiction, index) => (
        <li
          key={`${contradiction.left ?? 'left'}-${contradiction.right ?? 'right'}-${contradiction.field ?? index}`}
          className="rounded-lg border border-border bg-card p-4"
        >
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
            <span className="text-sm font-medium text-foreground">
              {contradiction.field ? `Field: ${contradiction.field}` : 'Field conflict'}
            </span>
            <StatusBadge kind="severity" value={contradiction.severity} />
            {contradiction.detectedAt ? (
              <span className="text-xs text-muted-foreground">
                detected <TimestampDisplay value={contradiction.detectedAt} />
              </span>
            ) : null}
          </div>

          {contradiction.detail ? (
            <p className="mt-1.5 text-sm text-muted-foreground">{contradiction.detail}</p>
          ) : null}

          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <ContradictionSide
              caption="Left"
              evidenceId={contradiction.left}
              value={contradiction.leftValue}
            />
            <ContradictionSide
              caption="Right"
              evidenceId={contradiction.right}
              value={contradiction.rightValue}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

function ContradictionSide({
  caption,
  evidenceId,
  value,
}: {
  caption: string;
  evidenceId: string | null;
  value: string | null;
}) {
  return (
    <div className="min-w-0 rounded-md border border-border p-3">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{caption}</p>
      {evidenceId ? (
        <Link
          href={`/evidence/${evidenceId}`}
          className="mono-id mt-0.5 block truncate text-sm underline-offset-4 hover:underline"
        >
          {evidenceId}
        </Link>
      ) : (
        <span className="mt-0.5 block text-sm text-muted-foreground">Unattributed</span>
      )}
      <p className="mt-1.5 break-words text-sm text-foreground">{value ?? '—'}</p>
    </div>
  );
}
