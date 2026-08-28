'use client';

import * as React from 'react';
import { ChevronDown, ChevronRight, Clock3, History, Shuffle } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { EmptyState } from './EmptyState';
import { JsonViewer } from './JsonViewer';
import { CopyableId } from './CopyableId';
import { formatDate, formatLatency, formatTime } from '@/lib/format/date';
import { humanizeEnum, humanizeEventType } from '@/lib/format/id';
import { useUiStore } from '@/lib/store/uiStore';
import type { AggregateType, TimelineEntry } from '@/lib/types/events';
import type { Iso8601 } from '@/lib/types/common';

export interface EventTimelineProps {
  entries: TimelineEntry[] | undefined;
  /**
   * Resolves the instant an entry was *observed* by the platform. `TimelineEntry.at` is the
   * business instant (`occurredAt`); the gap between the two is the whole point of the column.
   */
  observedAtFor?: (entry: TimelineEntry) => Iso8601 | null | undefined;
  /** Lag above which an entry is called out as late. Below it, clock skew is not news. */
  lateThresholdMs?: number;
  className?: string;
  emptyTitle?: string;
  emptyDescription?: React.ReactNode;
}

const AGGREGATE_ACCENT: Readonly<Record<AggregateType, string>> = {
  TRANSACTION: 'var(--chart-1)',
  PAYMENT: 'var(--chart-2)',
  ORDER: 'var(--chart-3)',
  SHIPMENT: 'var(--chart-4)',
  DELIVERY: 'var(--chart-6)',
  EVIDENCE: 'var(--chart-7)',
  REFUND: 'var(--chart-8)',
  COMMUNICATION: 'var(--chart-5)',
  MERCHANT: 'var(--status-neutral)',
  CUSTOMER: 'var(--status-neutral)',
  POLICY: 'var(--status-neutral)',
  DISPUTE: 'var(--status-neutral)',
  CASE: 'var(--status-neutral)',
};

interface DecoratedEntry {
  entry: TimelineEntry;
  observedAt: Iso8601 | null;
  /** `observedAt - at`, in milliseconds. Null when the platform did not report an observation. */
  lagMs: number | null;
  /** True when this entry was observed *after* an entry that occurred later than it. */
  outOfOrder: boolean;
}

/**
 * The unified event + evidence timeline of `GET /transactions/{id}/timeline`.
 *
 * Two clocks are shown, never one. `occurredAt` is when the world changed; `observedAt` is when
 * this platform found out. Contract 17 rules 9 and 10 require every consumer to tolerate late
 * and out-of-order events, and a timeline that silently sorts by arrival hides exactly the
 * failure those rules exist for - so entries are ordered by `occurredAt` and anything whose
 * observation ran behind is labelled where it sits.
 */
export function EventTimeline({
  entries,
  observedAtFor,
  lateThresholdMs = 60_000,
  className,
  emptyTitle = 'No timeline entries',
  emptyDescription = 'Nothing has been ingested for this transaction yet. Events arrive on pdei.canonical.events.v1 and evidence on pdei.evidence.events.v1.',
}: EventTimelineProps) {
  const timeZoneMode = useUiStore((state) => state.timeZoneMode);

  const decorated = React.useMemo<DecoratedEntry[]>(() => {
    if (!entries || entries.length === 0) return [];
    const ordered = [...entries].sort(
      (a, b) => new Date(a.at).getTime() - new Date(b.at).getTime(),
    );
    let highWaterMark = Number.NEGATIVE_INFINITY;
    return ordered.map((entry) => {
      const observedAt = observedAtFor?.(entry) ?? null;
      const occurredMs = new Date(entry.at).getTime();
      const observedMs = observedAt ? new Date(observedAt).getTime() : null;
      const lagMs = observedMs !== null && Number.isFinite(observedMs) ? observedMs - occurredMs : null;
      // Out of order == this entry was observed before something that occurred earlier.
      const outOfOrder = observedMs !== null && observedMs < highWaterMark;
      if (observedMs !== null) highWaterMark = Math.max(highWaterMark, observedMs);
      return { entry, observedAt, lagMs, outOfOrder };
    });
  }, [entries, observedAtFor]);

  if (decorated.length === 0) {
    return <EmptyState icon={History} title={emptyTitle} description={emptyDescription} className={className} />;
  }

  const lateCount = decorated.filter((item) => (item.lagMs ?? 0) > lateThresholdMs).length;
  const outOfOrderCount = decorated.filter((item) => item.outOfOrder).length;

  // Group by UTC calendar day so the date is written once, not on every row.
  const groups: { day: string; rows: DecoratedEntry[] }[] = [];
  for (const item of decorated) {
    const day = formatDate(item.entry.at, timeZoneMode);
    const last = groups[groups.length - 1];
    if (last && last.day === day) last.rows.push(item);
    else groups.push({ day, rows: [item] });
  }

  return (
    <div className={cn('space-y-4', className)}>
      <p className="text-xs text-muted-foreground">
        Ordered by <strong className="font-medium text-foreground">occurredAt</strong>. The second
        instant on each row is <strong className="font-medium text-foreground">observedAt</strong> -
        when the platform saw it.
        {lateCount > 0 ? ` ${lateCount} arrived late.` : ''}
        {outOfOrderCount > 0 ? ` ${outOfOrderCount} arrived out of order.` : ''}
      </p>

      <ol className="space-y-6">
        {groups.map((group) => (
          <li key={group.day}>
            <h3 className="pb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {group.day}
            </h3>
            <ol className="relative space-y-0 border-l border-border pl-0">
              {group.rows.map((row) => (
                <TimelineRow
                  key={row.entry.entryId}
                  row={row}
                  timeZoneMode={timeZoneMode}
                  lateThresholdMs={lateThresholdMs}
                />
              ))}
            </ol>
          </li>
        ))}
      </ol>
    </div>
  );
}

function TimelineRow({
  row,
  timeZoneMode,
  lateThresholdMs,
}: {
  row: DecoratedEntry;
  timeZoneMode: 'utc' | 'local';
  lateThresholdMs: number;
}) {
  const [expanded, setExpanded] = React.useState(false);
  const { entry, observedAt, lagMs, outOfOrder } = row;
  const accent = AGGREGATE_ACCENT[entry.aggregateType] ?? 'var(--status-neutral)';
  const hasDetails = Object.keys(entry.details ?? {}).length > 0;
  const late = lagMs !== null && lagMs > lateThresholdMs;

  return (
    <li className="relative pb-5 pl-6 last:pb-0">
      <span
        className="absolute -left-[4.5px] top-1.5 size-[9px] rounded-full ring-2 ring-card"
        style={{ backgroundColor: accent }}
        aria-hidden
      />

      <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
        <time
          dateTime={entry.at}
          className="tabular text-sm font-medium text-foreground"
          title={`occurredAt ${entry.at}`}
        >
          {formatTime(entry.at, timeZoneMode)}
        </time>
        <span className="text-sm font-medium text-foreground">
          {humanizeEventType(entry.eventType)}
        </span>
        <Badge variant="subtle" className="text-2xs">
          {humanizeEnum(entry.aggregateType)}
        </Badge>
        {late ? (
          <span
            className="inline-flex items-center gap-1 text-2xs font-medium"
            style={{ color: 'var(--status-warning)' }}
            title={`occurredAt ${entry.at}; observedAt ${observedAt}`}
          >
            <Clock3 className="size-3" aria-hidden />
            observed {formatLatency(lagMs ?? 0)} late
          </span>
        ) : null}
        {outOfOrder ? (
          <span
            className="inline-flex items-center gap-1 text-2xs font-medium"
            style={{ color: 'var(--status-serious)' }}
            title="This entry was observed after an entry that occurred later than it."
          >
            <Shuffle className="size-3" aria-hidden />
            out of order
          </span>
        ) : null}
      </div>

      <p className="mt-0.5 text-sm text-muted-foreground">{entry.summary}</p>

      <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <CopyableId id={entry.aggregateId} shorten className="text-xs" />
        <span>{humanizeEnum(entry.source)}</span>
        <span title={observedAt ? `observedAt ${observedAt}` : 'The platform did not report an observation instant for this entry.'}>
          observed{' '}
          {observedAt ? (
            <time dateTime={observedAt} className="tabular">
              {formatTime(observedAt, timeZoneMode)}
            </time>
          ) : (
            '-'
          )}
        </span>
        {hasDetails ? (
          <button
            type="button"
            onClick={() => setExpanded((current) => !current)}
            aria-expanded={expanded}
            className="inline-flex items-center gap-1 rounded-sm text-muted-foreground hover:text-foreground"
          >
            {expanded ? <ChevronDown className="size-3" aria-hidden /> : <ChevronRight className="size-3" aria-hidden />}
            {expanded ? 'Hide payload' : 'Show payload'}
          </button>
        ) : null}
      </div>

      {expanded && hasDetails ? (
        <JsonViewer value={entry.details} className="mt-2" defaultExpandedDepth={2} maxHeight="16rem" />
      ) : null}
    </li>
  );
}
