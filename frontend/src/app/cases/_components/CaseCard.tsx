'use client';

import Link from 'next/link';
import { AlertTriangle, ArrowRight, UserCheck } from 'lucide-react';
import { cn } from '@/lib/utils';
import { DeadlineCountdown } from '@/components/shared/DeadlineCountdown';
import { MoneyDisplay } from '@/components/shared/MoneyDisplay';
import { ReadinessBadge } from '@/components/shared/ReadinessBadge';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { humanizeEnum, shortenId } from '@/lib/format/id';
import type { CaseQueueRow } from './caseQueue';

export interface CaseCardProps {
  row: CaseQueueRow;
  merchantLabel: string;
  /** True when a CASE_UPDATED frame for this case arrived in the last few seconds. */
  live?: boolean;
}

/**
 * One case in the queue.
 *
 * The card answers, in this order: how much is at risk, what the network claims, how long is
 * left, whether we can defend it, and whether a person is blocking the workflow. Deadline
 * urgency uses the contract 9.4 window (< 48h) and is always carried by an icon and words,
 * never by colour alone.
 */
export function CaseCard({ row, merchantLabel, live = false }: CaseCardProps) {
  return (
    <Link
      href={`/cases/${row.caseId}`}
      className={cn(
        'group block rounded-lg border border-border bg-card p-3 transition-colors',
        'hover:border-primary/50 hover:bg-accent/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        row.awaitingHuman && 'border-[color:var(--status-warning)]/45',
        row.failed && 'border-[color:var(--status-critical)]/45',
        live && 'ring-1 ring-primary/60',
      )}
      aria-label={`Case ${row.caseId} for ${merchantLabel}`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="mono-id truncate text-foreground">{shortenId(row.caseId, 12, 4)}</p>
          <p className="truncate text-2xs text-muted-foreground">{merchantLabel}</p>
        </div>
        <MoneyDisplay money={row.amountAtRisk} className="text-sm font-semibold" />
      </div>

      <p className="mt-2 text-xs font-medium text-foreground">
        {row.reasonCode ? humanizeEnum(row.reasonCode) : 'Reason code unavailable'}
      </p>

      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <DeadlineCountdown deadlineAt={row.deadlineAt} className="text-2xs" />

        <ReadinessBadge
          band={row.readinessBand}
          score={row.readinessScore}
          size="sm"
          className="shrink-0"
        />
      </div>

      <div className="mt-2.5 flex items-center justify-between gap-2 border-t border-border pt-2">
        <span className="text-2xs text-muted-foreground">
          updated <TimestampDisplay value={row.updatedAt} mode="relative" className="text-2xs" />
        </span>
        {row.awaitingHuman ? (
          <span
            className="inline-flex items-center gap-1 text-2xs font-medium"
            style={{ color: 'var(--status-warning)' }}
          >
            <UserCheck className="size-3" aria-hidden />
            waiting on you
          </span>
        ) : row.failed ? (
          <span
            className="inline-flex items-center gap-1 text-2xs font-medium"
            style={{ color: 'var(--status-critical)' }}
          >
            <AlertTriangle className="size-3" aria-hidden />
            workflow failed
          </span>
        ) : (
          <ArrowRight
            className="size-3.5 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100"
            aria-hidden
          />
        )}
      </div>
    </Link>
  );
}
