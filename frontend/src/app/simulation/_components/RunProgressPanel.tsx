'use client';

import * as React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, Square } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import { toast } from '@/components/ui/sonner';
import { ConfirmDialog } from '@/components/shared/ConfirmDialog';
import { EmptyState } from '@/components/shared/EmptyState';
import { ErrorState } from '@/components/shared/ErrorState';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { JsonViewer } from '@/components/shared/JsonViewer';
import { simulationApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { formatSpan } from '@/lib/format/date';
import type { SimulationRun } from '@/lib/types/simulation';

/** Poll interval while a run is still producing events. */
const LIVE_POLL_MS = 2_000;

const ACTIVE_STATUSES: readonly SimulationRun['status'][] = ['PENDING', 'RUNNING'];

export interface RunProgressPanelProps {
  runId: string | null;
  onRunFinished?: (run: SimulationRun) => void;
}

/**
 * Live progress of one simulation run.
 *
 * The run itself is server-side and asynchronous, so this polls `GET /sim/v1/runs/{runId}`
 * while the run is PENDING or RUNNING and stops the moment it reaches a terminal status -
 * polling a finished run forever is how a demo console quietly becomes a load generator.
 */
export function RunProgressPanel({ runId, onRunFinished }: RunProgressPanelProps) {
  const queryClient = useQueryClient();
  const [stopOpen, setStopOpen] = React.useState(false);
  const finishedRef = React.useRef<string | null>(null);

  const runQuery = useQuery({
    queryKey: queryKeys.simulation.run(runId ?? 'none'),
    queryFn: ({ signal }) => simulationApi.getRun(runId as string, signal),
    enabled: Boolean(runId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status && ACTIVE_STATUSES.includes(status) ? LIVE_POLL_MS : false;
    },
  });

  const run = runQuery.data;

  React.useEffect(() => {
    if (!run || ACTIVE_STATUSES.includes(run.status)) return;
    if (finishedRef.current === run.runId) return;
    finishedRef.current = run.runId;
    onRunFinished?.(run);
    void queryClient.invalidateQueries({ queryKey: queryKeys.simulation.runs() });
  }, [run, onRunFinished, queryClient]);

  const stopMutation = useMutation({
    mutationFn: () => simulationApi.stopRun(runId as string),
    onSuccess: (stopped) => {
      queryClient.setQueryData(queryKeys.simulation.run(stopped.runId), stopped);
      void queryClient.invalidateQueries({ queryKey: queryKeys.simulation.runs() });
      toast.success(`Run ${stopped.runId} stopped`);
    },
    onError: (error: Error) => toast.error('Stop failed', { description: error.message }),
  });

  if (!runId) {
    return (
      <EmptyState
        icon={Activity}
        compact
        title="No run selected"
        description="Start a run or pick one from the history below to watch it progress."
      />
    );
  }

  if (runQuery.isError) {
    return <ErrorState error={runQuery.error} onRetry={() => void runQuery.refetch()} compact />;
  }

  if (!run) {
    return <EmptyState icon={Activity} compact title="Loading run" description="Fetching progress." />;
  }

  const active = ACTIVE_STATUSES.includes(run.status);

  return (
    <section className="surface-card p-4" aria-label={`Progress of run ${run.runId}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <Activity className="size-4 text-muted-foreground" aria-hidden />
            <span className="mono-id">{run.runId}</span>
          </h2>
          <p className="mt-1 flex flex-wrap items-center gap-2 text-2xs text-muted-foreground">
            <span className="tabular">seed {run.seed}</span>
            <span>·</span>
            <span className="tabular">
              {run.merchantCount} merchants · {run.transactionCount} transactions · {run.days} days
            </span>
            <span>·</span>
            <span className="tabular">{run.disputeRateBps} bps dispute rate</span>
            {run.failureProfile ? (
              <>
                <span>·</span>
                <Badge variant="subtle" className="text-2xs">
                  {run.failureProfile}
                </Badge>
              </>
            ) : null}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <StatusBadge kind="simulation" value={run.status} />
          {active ? (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setStopOpen(true)}
              disabled={stopMutation.isPending}
            >
              <Square className="size-3.5" />
              Stop
            </Button>
          ) : null}
        </div>
      </div>

      <div className="mt-3">
        <div className="flex items-baseline justify-between text-xs">
          <span className="text-muted-foreground">Progress</span>
          <span className="tabular font-medium text-foreground">{run.progressPercent}%</span>
        </div>
        <Progress
          className="mt-1.5"
          value={run.progressPercent}
          indicatorColor={
            run.status === 'FAILED'
              ? 'var(--status-critical)'
              : run.status === 'COMPLETED'
                ? 'var(--status-good)'
                : 'var(--chart-1)'
          }
          aria-label={`Run ${run.runId} progress`}
        />
      </div>

      <dl className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Counter label="Events emitted" value={run.eventsEmitted} />
        <Counter label="Transactions" value={run.transactionsCreated} />
        <Counter label="Evidence" value={run.evidenceCreated} />
        <Counter label="Disputes" value={run.disputesCreated} />
      </dl>

      <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-2xs text-muted-foreground">
        {run.startedAt ? (
          <span>
            started <TimestampDisplay value={run.startedAt} className="text-2xs" />
          </span>
        ) : null}
        {run.finishedAt ? (
          <span>
            finished <TimestampDisplay value={run.finishedAt} className="text-2xs" />
          </span>
        ) : null}
        {run.startedAt && run.finishedAt ? (
          <span>took {formatSpan(run.startedAt, run.finishedAt)}</span>
        ) : null}
        {run.requestedBy ? <span>requested by {run.requestedBy}</span> : null}
        {active ? <span>polling every {LIVE_POLL_MS / 1000}s</span> : null}
      </div>

      {run.errorMessage ? (
        <p className="mt-2 text-xs" style={{ color: 'var(--status-critical)' }}>
          {run.errorMessage}
        </p>
      ) : null}

      {Object.keys(run.stats ?? {}).length > 0 ? (
        <details className="mt-3">
          <summary className="cursor-pointer text-2xs text-muted-foreground">
            Run statistics
          </summary>
          <JsonViewer className="mt-2" value={run.stats} defaultExpandedDepth={2} maxHeight="14rem" />
        </details>
      ) : null}

      <ConfirmDialog
        open={stopOpen}
        onOpenChange={setStopOpen}
        title="Stop this run"
        description="Events already published stay on the topics and keep flowing through the workers. Stopping only prevents further generation."
        confirmLabel="Stop run"
        destructive
        onConfirm={async () => {
          await stopMutation.mutateAsync();
          setStopOpen(false);
        }}
      />
    </section>
  );
}

function Counter({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-2xs uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="tabular mt-0.5 text-lg font-semibold text-foreground">
        {value.toLocaleString('en-US')}
      </dd>
    </div>
  );
}
