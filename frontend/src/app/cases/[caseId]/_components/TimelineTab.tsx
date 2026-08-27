'use client';

import * as React from 'react';
import { Button } from '@/components/ui/button';
import { EventTimeline } from '@/components/shared/EventTimeline';
import type { AggregateType, TimelineEntry } from '@/lib/types/events';
import type { Iso8601 } from '@/lib/types/common';

/**
 * Filter groups that match how an investigator actually reads a case: what was bought, what was
 * proven, what the network claimed, and what the customer said.
 */
const FILTERS = [
  { id: 'all', label: 'Everything', types: null },
  {
    id: 'commerce',
    label: 'Commerce',
    types: ['TRANSACTION', 'PAYMENT', 'ORDER', 'SHIPMENT', 'DELIVERY', 'REFUND'],
  },
  { id: 'evidence', label: 'Evidence', types: ['EVIDENCE'] },
  { id: 'dispute', label: 'Dispute & case', types: ['DISPUTE', 'CASE'] },
  { id: 'contact', label: 'Customer contact', types: ['COMMUNICATION'] },
] as const;

type FilterId = (typeof FILTERS)[number]['id'];

/**
 * `TimelineEntry` carries only the business instant. The gateway puts the observation instant in
 * `details` when it has one, so the shared timeline can show both clocks; when it does not, the
 * lag column simply stays empty rather than inventing a value.
 */
function observedAtFor(entry: TimelineEntry): Iso8601 | null {
  const details = entry.details as Record<string, unknown> | undefined;
  const candidate = details?.['observedAt'] ?? details?.['receivedAt'];
  return typeof candidate === 'string' ? candidate : null;
}

export interface TimelineTabProps {
  entries: readonly TimelineEntry[];
}

/**
 * The unified transaction + evidence + case timeline (`core.timeline.TimelineService`).
 *
 * The rendering is the shared `EventTimeline`, which orders by `occurredAt` and calls out late
 * and out-of-order arrivals - exactly the behaviour contract 17 rules 9 and 10 require the
 * platform to tolerate. This tab adds only the case-specific lens: which slice of the story to
 * read.
 */
export function TimelineTab({ entries }: TimelineTabProps) {
  const [filter, setFilter] = React.useState<FilterId>('all');

  const filtered = React.useMemo(() => {
    const active = FILTERS.find((item) => item.id === filter);
    const allowed = active?.types as readonly AggregateType[] | null | undefined;
    if (!allowed) return [...entries];
    return entries.filter((entry) => allowed.includes(entry.aggregateType));
  }, [entries, filter]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        {FILTERS.map((item) => (
          <Button
            key={item.id}
            size="sm"
            variant={filter === item.id ? 'secondary' : 'ghost'}
            onClick={() => setFilter(item.id)}
            aria-pressed={filter === item.id}
          >
            {item.label}
          </Button>
        ))}
        <span className="ml-auto text-2xs text-muted-foreground">
          {filtered.length} of {entries.length} entries
        </span>
      </div>

      <EventTimeline
        entries={filtered}
        observedAtFor={observedAtFor}
        emptyTitle={
          filter === 'all' ? 'No timeline entries' : 'Nothing in this slice of the timeline'
        }
        emptyDescription={
          filter === 'all'
            ? "The gateway returned no unified timeline for this case's transaction. Events may still be in flight through normalization and state building."
            : 'Switch back to Everything to see the rest of the case history.'
        }
      />
    </div>
  );
}
