'use client';

import * as React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BookOpen, Play, Target, Timer } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { toast } from '@/components/ui/sonner';
import { ConfirmDialog } from '@/components/shared/ConfirmDialog';
import { EmptyState } from '@/components/shared/EmptyState';
import { ErrorState } from '@/components/shared/ErrorState';
import { LoadingState } from '@/components/shared/LoadingState';
import { simulationApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum } from '@/lib/format/id';
import type { Scenario, SimulationRun } from '@/lib/types/simulation';

export interface ScenarioLibraryProps {
  onScenarioStarted: (run: SimulationRun) => void;
}

/**
 * The curated scenario library (`GET /sim/v1/scenarios`).
 *
 * Each card states its expected outcome before it is run. That ordering is the whole value: a
 * demo that says what should happen and then shows it happening is evidence; a demo that only
 * shows something happening is a light show.
 */
export function ScenarioLibrary({ onScenarioStarted }: ScenarioLibraryProps) {
  const queryClient = useQueryClient();
  const [pending, setPending] = React.useState<Scenario | null>(null);

  const scenariosQuery = useQuery({
    queryKey: queryKeys.simulation.scenarios(),
    queryFn: ({ signal }) => simulationApi.listScenarios(signal),
    staleTime: 10 * 60_000,
  });

  const runMutation = useMutation({
    mutationFn: (key: string) => simulationApi.runScenario(key),
    // `{run, scenario}`, not a bare run (contract 8.5). Reading `runId` off the envelope gave
    // `undefined`, so the toast named nothing and the run was never selected.
    onSuccess: ({ run, scenario }) => {
      onScenarioStarted(run);
      void queryClient.invalidateQueries({ queryKey: queryKeys.simulation.runs() });
      toast.success(`Scenario started as ${run.runId}`, {
        description: `Expect ${humanizeEnum(scenario.expected.readinessBand)} at score ${
          scenario.expected.scoreMin
        }-${scenario.expected.scoreMax}.`,
      });
    },
    onError: (error: Error) => toast.error('Scenario failed to start', { description: error.message }),
  });

  return (
    <section className="space-y-3" aria-label="Scenario library">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <BookOpen className="size-4 text-muted-foreground" aria-hidden />
          Scenario library
        </h2>
        <span className="text-2xs text-muted-foreground">
          GET <span className="mono-id">/sim/v1/scenarios</span>
        </span>
      </div>

      {scenariosQuery.isLoading ? (
        <LoadingState variant="cards" count={4} label="Loading scenarios" />
      ) : scenariosQuery.isError ? (
        <ErrorState
          error={scenariosQuery.error}
          onRetry={() => void scenariosQuery.refetch()}
          title="simulator-service is not reachable"
          compact
        />
      ) : (scenariosQuery.data ?? []).length === 0 ? (
        <EmptyState
          icon={BookOpen}
          compact
          title="No curated scenarios"
          description="simulator-service returned an empty scenario list. Scenarios are defined server-side, not here."
        />
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {(scenariosQuery.data ?? []).map((scenario) => (
            <article
              key={scenario.key}
              className="surface-card flex flex-col gap-2 p-4"
              aria-label={scenario.title}
            >
              <div className="flex items-start justify-between gap-2">
                <h3 className="text-sm font-semibold text-foreground">{scenario.title}</h3>
                <span className="inline-flex shrink-0 items-center gap-1 text-2xs text-muted-foreground">
                  <Timer className="size-3" aria-hidden />
                  {scenario.transactions} tx
                </span>
              </div>

              <p className="text-xs text-muted-foreground">{scenario.description}</p>

              {/*
                The expectation block is the point of the card: it is stated before the run, so
                the run either reproduces it or visibly does not. Band and classification are the
                claim; `aiPath` is the cost claim - DETERMINISTIC means no model was needed.
              */}
              <div className="rounded-md border border-primary/30 bg-primary/5 p-2.5">
                <p className="flex items-center gap-1.5 text-2xs font-medium uppercase tracking-wide text-primary">
                  <Target className="size-3" aria-hidden />
                  Expected
                </p>
                <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                  <Badge variant="subtle" className="text-2xs">
                    {humanizeEnum(scenario.expected.readinessBand)}
                  </Badge>
                  <span className="tabular text-2xs text-muted-foreground">
                    score {scenario.expected.scoreMin}-{scenario.expected.scoreMax}
                  </span>
                  <Badge variant="subtle" className="text-2xs">
                    {humanizeEnum(scenario.expected.aiPath)}
                  </Badge>
                </div>
                <p className="mt-1.5 text-xs text-foreground">
                  {humanizeEnum(scenario.expected.classification)} -{' '}
                  {humanizeEnum(scenario.expected.recommendedAction)}
                </p>
              </div>

              {scenario.expected.gapTypes.length > 0 ? (
                <div className="flex flex-wrap gap-1.5">
                  {scenario.expected.gapTypes.map((type) => (
                    <Badge key={type} variant="subtle" className="text-2xs">
                      {humanizeEnum(type)}
                    </Badge>
                  ))}
                </div>
              ) : null}

              <p className="text-2xs leading-relaxed text-muted-foreground">{scenario.demoNote}</p>

              <Button
                size="sm"
                className="mt-auto w-full"
                onClick={() => setPending(scenario)}
                disabled={runMutation.isPending}
              >
                <Play className="size-3.5" />
                Run scenario
              </Button>
            </article>
          ))}
        </div>
      )}

      <ConfirmDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open) setPending(null);
        }}
        title={pending ? `Run “${pending.title}”` : 'Run scenario'}
        description={
          pending ? (
            <>
              {pending.description} Watch for: readiness{' '}
              {humanizeEnum(pending.expected.readinessBand)} at score{' '}
              {pending.expected.scoreMin}-{pending.expected.scoreMax}, classified{' '}
              {humanizeEnum(pending.expected.classification)} via the{' '}
              {humanizeEnum(pending.expected.aiPath)} path.
            </>
          ) : null
        }
        confirmLabel="Run scenario"
        onConfirm={async () => {
          if (!pending) return;
          await runMutation.mutateAsync(pending.key);
          setPending(null);
        }}
      />
    </section>
  );
}
