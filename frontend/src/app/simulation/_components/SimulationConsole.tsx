'use client';

import * as React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FlaskConical, RefreshCw } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { toast } from '@/components/ui/sonner';
import { DataTable, type DataTableColumn } from '@/components/shared/DataTable';
import { PageHeader } from '@/components/shared/PageHeader';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { simulationApi } from '@/lib/api/endpoints';
import { newCorrelationId } from '@/lib/api/client';
import { queryKeys } from '@/lib/query/keys';
import { config } from '@/lib/config';
import { useSelectedMerchantId } from '@/lib/store/uiStore';
import type { SimulationRun, SimulationRunRequest } from '@/lib/types/simulation';
import { RunLauncher } from './RunLauncher';
import { RunProgressPanel } from './RunProgressPanel';
import { ScenarioLibrary } from './ScenarioLibrary';
import { ChaosPanel } from './ChaosPanel';
import { InjectionHistory } from './InjectionHistory';
import { ReplayPanel } from './ReplayPanel';
import { EventTicker } from './EventTicker';

/**
 * The Simulation and Chaos Console.
 *
 * One page, four questions: can we make a reproducible world (the launcher), what is it doing
 * right now (progress plus the live ticker), what breaks it (the chaos console and the scenario
 * library), and did anything actually break (the injection history and the duplicate counters).
 *
 * Everything here talks to simulator-service on its own base URL - it sits beside the gateway,
 * not behind it (contract 8.5) - so this page degrades independently of the rest of the console.
 */
export function SimulationConsole({ initialRunId }: { initialRunId: string | null }) {
  const merchantId = useSelectedMerchantId();
  const queryClient = useQueryClient();
  const [activeRunId, setActiveRunId] = React.useState<string | null>(initialRunId);
  const [pulseKey, setPulseKey] = React.useState(0);

  const runsQuery = useQuery({
    queryKey: queryKeys.simulation.runs(),
    queryFn: ({ signal }) => simulationApi.listRuns(signal),
  });

  const runs = React.useMemo(
    () =>
      [...(runsQuery.data ?? [])].sort((a, b) =>
        (b.startedAt ?? '').localeCompare(a.startedAt ?? ''),
      ),
    [runsQuery.data],
  );

  React.useEffect(() => {
    if (activeRunId) return;
    const running = runs.find((run) => run.status === 'RUNNING' || run.status === 'PENDING');
    setActiveRunId(running?.runId ?? runs[0]?.runId ?? null);
  }, [runs, activeRunId]);

  const startMutation = useMutation({
    mutationFn: (request: SimulationRunRequest) =>
      simulationApi.startRun(request, newCorrelationId()),
    onSuccess: (run) => {
      setActiveRunId(run.runId);
      queryClient.setQueryData(queryKeys.simulation.run(run.runId), run);
      void queryClient.invalidateQueries({ queryKey: queryKeys.simulation.runs() });
      toast.success(`Run ${run.runId} started`, {
        description: `seed ${run.seed} · ${run.transactionCount} transactions`,
      });
    },
    onError: (error: Error) => toast.error('Run failed to start', { description: error.message }),
  });

  const pulse = React.useCallback(() => setPulseKey((value) => value + 1), []);

  const runColumns: DataTableColumn<SimulationRun>[] = [
    {
      id: 'runId',
      header: 'Run',
      cell: (row) => (
        <span className={cn('mono-id text-xs', row.runId === activeRunId && 'text-primary')}>
          {row.runId}
        </span>
      ),
      sortValue: (row) => row.runId,
    },
    {
      id: 'status',
      header: 'Status',
      cell: (row) => <StatusBadge kind="simulation" value={row.status} />,
      sortValue: (row) => row.status,
    },
    {
      id: 'progress',
      header: 'Progress',
      align: 'right',
      cell: (row) => <span className="tabular text-xs">{row.progressPercent}%</span>,
      sortValue: (row) => row.progressPercent,
    },
    {
      id: 'seed',
      header: 'Seed',
      align: 'right',
      hideBelowSm: true,
      cell: (row) => <span className="tabular text-xs">{row.seed}</span>,
      sortValue: (row) => row.seed,
    },
    {
      id: 'scale',
      header: 'Scale',
      hideBelowSm: true,
      cell: (row) => (
        <span className="tabular text-2xs text-muted-foreground">
          {row.merchantCount}m · {row.transactionCount}tx · {row.days}d
        </span>
      ),
    },
    {
      id: 'profile',
      header: 'Profile',
      hideBelowSm: true,
      cell: (row) =>
        row.failureProfile ? (
          <Badge variant="subtle" className="text-2xs">
            {row.failureProfile}
          </Badge>
        ) : (
          <span className="text-2xs text-muted-foreground">—</span>
        ),
      sortValue: (row) => row.failureProfile ?? '',
    },
    {
      id: 'events',
      header: 'Events',
      align: 'right',
      hideBelowSm: true,
      cell: (row) => <span className="tabular text-xs">{row.eventsEmitted.toLocaleString('en-US')}</span>,
      sortValue: (row) => row.eventsEmitted,
    },
    {
      id: 'startedAt',
      header: 'Started',
      cell: (row) => <TimestampDisplay value={row.startedAt} className="text-xs" />,
      sortValue: (row) => row.startedAt ?? '',
    },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Verify"
        title="Simulation & chaos"
        description="Reproducible workloads and deliberate failures. Nothing on this page is a mock: runs publish real events onto the real topics, and injections damage real state so the platform's response can be observed rather than asserted."
        meta={
          <>
            <Badge variant="outline" className="mono-id">
              {config.simBaseUrl}
            </Badge>
            {merchantId ? <Badge variant="subtle">{merchantId}</Badge> : null}
          </>
        }
        actions={
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              void runsQuery.refetch();
              void queryClient.invalidateQueries({ queryKey: queryKeys.simulation.all() });
            }}
            disabled={runsQuery.isFetching}
          >
            <RefreshCw className={cn('size-3.5', runsQuery.isFetching && 'animate-spin')} />
            Refresh
          </Button>
        }
      />

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_24rem]">
        <div className="space-y-4">
          <RunLauncher
            onStart={(request) => startMutation.mutateAsync(request)}
            busy={startMutation.isPending}
          />
          <RunProgressPanel
            runId={activeRunId}
            onRunFinished={(run) =>
              toast.info(`Run ${run.runId} ${run.status.toLowerCase()}`, {
                description: `${run.eventsEmitted} events · ${run.disputesCreated} disputes`,
              })
            }
          />
        </div>

        <EventTicker pulseKey={pulseKey} className="xl:sticky xl:top-4 xl:self-start" />
      </div>

      <section className="space-y-3" aria-label="Run history">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
            <FlaskConical className="size-4 text-muted-foreground" aria-hidden />
            Run history
          </h2>
          <span className="text-2xs text-muted-foreground">
            Select a row to watch that run above
          </span>
        </div>
        <DataTable
          columns={runColumns}
          rows={runs}
          getRowId={(row) => row.runId}
          isLoading={runsQuery.isLoading}
          error={runsQuery.isError ? runsQuery.error : undefined}
          onRetry={() => void runsQuery.refetch()}
          onRowClick={(row) => setActiveRunId(row.runId)}
          initialSort={{ columnId: 'startedAt', direction: 'desc' }}
          emptyTitle="No simulation runs"
          emptyDescription="Start one above, or run a curated scenario. Runs are persisted in pdei.simulation_runs and survive a restart."
        />
      </section>

      <ScenarioLibrary
        onScenarioStarted={(run) => {
          setActiveRunId(run.runId);
          pulse();
        }}
      />

      <ChaosPanel runId={activeRunId} onInjected={pulse} />

      <InjectionHistory />

      <ReplayPanel onReplayed={pulse} />
    </div>
  );
}
