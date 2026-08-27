'use client';

import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import { History } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { DataTable, type DataTableColumn } from '@/components/shared/DataTable';
import { CopyableId } from '@/components/shared/CopyableId';
import { JsonViewer } from '@/components/shared/JsonViewer';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { simulationApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { formatLatency } from '@/lib/format/date';
import { humanizeEnum } from '@/lib/format/id';
import type { ChaosInjection } from '@/lib/types/simulation';

/** Summarises `ChaosRequest.target` into one line without hiding what it contained. */
function targetSummary(target: Record<string, unknown>): string {
  const entries = Object.entries(target ?? {});
  if (entries.length === 0) return '—';
  return entries.map(([key, value]) => `${key}=${String(value)}`).join(' · ');
}

export function InjectionHistory() {
  const [expanded, setExpanded] = React.useState<string | null>(null);

  const chaosQuery = useQuery({
    queryKey: queryKeys.simulation.chaos(),
    queryFn: ({ signal }) => simulationApi.listChaos(signal),
  });

  const rows = React.useMemo(
    () =>
      [...(chaosQuery.data ?? [])].sort((a, b) => b.injectedAt.localeCompare(a.injectedAt)),
    [chaosQuery.data],
  );

  const columns: DataTableColumn<ChaosInjection>[] = [
    {
      id: 'injectedAt',
      header: 'Injected',
      cell: (row) => <TimestampDisplay value={row.injectedAt} className="text-xs" />,
      sortValue: (row) => row.injectedAt,
    },
    {
      id: 'type',
      header: 'Type',
      cell: (row) => <span className="text-xs font-medium">{humanizeEnum(row.type)}</span>,
      sortValue: (row) => row.type,
    },
    {
      id: 'status',
      header: 'Status',
      cell: (row) => <StatusBadge kind="chaos" value={row.status} />,
      sortValue: (row) => row.status,
    },
    {
      id: 'target',
      header: 'Target',
      cell: (row) => (
        <span className="mono-id text-2xs text-muted-foreground">{targetSummary(row.target)}</span>
      ),
    },
    {
      id: 'delay',
      header: 'Delay',
      align: 'right',
      hideBelowSm: true,
      cell: (row) => (
        <span className="tabular text-xs">
          {row.delayMs === null ? '—' : formatLatency(row.delayMs)}
        </span>
      ),
      sortValue: (row) => row.delayMs ?? 0,
    },
    {
      id: 'count',
      header: 'Count',
      align: 'right',
      hideBelowSm: true,
      cell: (row) => <span className="tabular text-xs">{row.eventCount ?? '—'}</span>,
      sortValue: (row) => row.eventCount ?? 0,
    },
    {
      id: 'run',
      header: 'Run',
      hideBelowSm: true,
      cell: (row) =>
        row.runId ? (
          <CopyableId id={row.runId} shorten />
        ) : (
          <span className="text-2xs text-muted-foreground">ad hoc</span>
        ),
    },
    {
      id: 'actor',
      header: 'Actor',
      hideBelowSm: true,
      cell: (row) => <span className="text-2xs text-muted-foreground">{row.actor ?? '—'}</span>,
    },
    {
      id: 'details',
      header: '',
      align: 'right',
      width: '5rem',
      cell: (row) => (
        <Button
          size="sm"
          variant="ghost"
          onClick={(event) => {
            event.stopPropagation();
            setExpanded((current) => (current === row.injectionId ? null : row.injectionId));
          }}
          aria-expanded={expanded === row.injectionId}
        >
          {expanded === row.injectionId ? 'Hide' : 'Details'}
        </Button>
      ),
    },
  ];

  const expandedRow = rows.find((row) => row.injectionId === expanded) ?? null;

  return (
    <section className="space-y-3" aria-label="Injection history">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <History className="size-4 text-muted-foreground" aria-hidden />
          Injection history
        </h2>
        <span className="text-2xs text-muted-foreground">
          GET <span className="mono-id">/sim/v1/chaos</span> · persisted in{' '}
          <span className="mono-id">pdei.chaos_injections</span>
        </span>
      </div>

      <DataTable
        columns={columns}
        rows={rows}
        getRowId={(row) => row.injectionId}
        isLoading={chaosQuery.isLoading}
        error={chaosQuery.isError ? chaosQuery.error : undefined}
        onRetry={() => void chaosQuery.refetch()}
        initialSort={{ columnId: 'injectedAt', direction: 'desc' }}
        emptyTitle="No injections yet"
        emptyDescription="Nothing has been injected against this platform. Use the chaos console above; every injection is recorded here with its target and outcome."
        caption={`${rows.length} injection${rows.length === 1 ? '' : 's'} recorded`}
      />

      {expandedRow ? (
        <div className="surface-card space-y-3 p-4">
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <CopyableId id={expandedRow.injectionId} link={false} />
            <StatusBadge kind="chaos" value={expandedRow.status} />
            {expandedRow.completedAt ? (
              <span className="text-2xs text-muted-foreground">
                completed <TimestampDisplay value={expandedRow.completedAt} className="text-2xs" />
              </span>
            ) : null}
          </div>
          {expandedRow.errorMessage ? (
            <p className="text-xs" style={{ color: 'var(--status-critical)' }}>
              {expandedRow.errorMessage}
            </p>
          ) : null}
          <div className="grid gap-3 lg:grid-cols-2">
            <div>
              <p className="text-2xs uppercase tracking-wide text-muted-foreground">Target</p>
              <JsonViewer className="mt-1" value={expandedRow.target} defaultExpandedDepth={2} maxHeight="12rem" />
            </div>
            <div>
              <p className="text-2xs uppercase tracking-wide text-muted-foreground">Result</p>
              <JsonViewer className="mt-1" value={expandedRow.result} defaultExpandedDepth={2} maxHeight="12rem" />
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}
