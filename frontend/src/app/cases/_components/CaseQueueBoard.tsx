'use client';

import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Layers, RefreshCw, Radio } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { PageHeader } from '@/components/shared/PageHeader';
import { EmptyState } from '@/components/shared/EmptyState';
import { ErrorState } from '@/components/shared/ErrorState';
import { LoadingState } from '@/components/shared/LoadingState';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { StatTile } from '@/components/shared/StatTile';
import { MoneyDisplay } from '@/components/shared/MoneyDisplay';
import { casesApi, disputesApi, merchantsApi, transactionsApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { useSelectedMerchantId } from '@/lib/store/uiStore';
import { useLiveEventsOfType } from '@/lib/store/liveStore';
import { CASE_STATUS_LANES } from '@/lib/types/case';
import type { CaseStatus } from '@/lib/types/case';
import { CaseCard } from './CaseCard';
import { buildQueueRows, groupByLane, laneExposure, type CaseQueueRow } from './caseQueue';

/** How long a case stays visually "live" after a CASE_UPDATED frame mentioning it. */
const LIVE_HIGHLIGHT_MS = 30_000;

/** Enough rows to hold a merchant's whole queue in one board; the gateway caps at 200. */
const FETCH_SIZE = 200;

const LANE_DESCRIPTION: Readonly<Record<CaseStatus, string>> = {
  CREATED: 'Workflow started; the case exists but nothing has been gathered yet.',
  ASSEMBLING: 'gatherEvidence is running: linking every artifact on the transaction.',
  AWAITING_EVIDENCE: 'Parked on the evidenceArrived signal, up to 7 days.',
  INVESTIGATING: 'Admission control passed; the case is with the reasoner.',
  AWAITING_APPROVAL: 'Parked on the humanDecision signal. Nothing moves without a person.',
  PREPARED: 'Representment package assembled and ready to submit.',
  SUBMITTED: 'Submitted to the network; following up until it closes.',
  CLOSED: 'Terminal. Won, lost, expired or withdrawn.',
  FAILED: 'The workflow failed. An operator has to look at this.',
};

export function CaseQueueBoard() {
  const merchantId = useSelectedMerchantId();
  const [tick, setTick] = React.useState(0);

  const casesQuery = useQuery({
    queryKey: queryKeys.cases.list({ merchantId: merchantId ?? undefined, size: FETCH_SIZE }),
    queryFn: ({ signal }) =>
      casesApi.list({ merchantId: merchantId ?? undefined, size: FETCH_SIZE }, signal),
    enabled: Boolean(merchantId),
  });

  const disputesQuery = useQuery({
    queryKey: queryKeys.disputes.list({ merchantId: merchantId ?? undefined, size: FETCH_SIZE }),
    queryFn: ({ signal }) =>
      disputesApi.list({ merchantId: merchantId ?? undefined, size: FETCH_SIZE }, signal),
    enabled: Boolean(merchantId),
  });

  const transactionsQuery = useQuery({
    queryKey: queryKeys.transactions.list({ merchantId: merchantId ?? undefined, size: FETCH_SIZE }),
    queryFn: ({ signal }) =>
      transactionsApi.list({ merchantId: merchantId ?? undefined, size: FETCH_SIZE }, signal),
    enabled: Boolean(merchantId),
  });

  const merchantsQuery = useQuery({
    queryKey: queryKeys.merchants.list({ size: 100 }),
    queryFn: ({ signal }) => merchantsApi.list({ size: 100 }, signal),
    staleTime: 5 * 60_000,
  });

  const merchantLabel = React.useMemo(() => {
    const found = merchantsQuery.data?.content.find((item) => item.merchantId === merchantId);
    return found?.displayName ?? merchantId ?? 'No merchant selected';
  }, [merchantsQuery.data, merchantId]);

  // CASE_UPDATED frames already invalidate the case queries (useInvalidateOnWsEvent). Here we
  // only use the tail to *show* which cards just moved, so a live change is visible and not
  // just silently re-fetched.
  const caseFrames = useLiveEventsOfType('CASE_UPDATED');

  React.useEffect(() => {
    if (caseFrames.length === 0) return;
    const timer = window.setInterval(() => setTick((value) => value + 1), 5_000);
    return () => window.clearInterval(timer);
  }, [caseFrames.length]);

  const liveCaseIds = React.useMemo(() => {
    void tick; // re-evaluated on the interval so the highlight expires on its own
    const now = Date.now();
    const ids = new Set<string>();
    for (const event of caseFrames) {
      if (now - Date.parse(event.receivedAt) > LIVE_HIGHLIGHT_MS) continue;
      const data = event.frame.data as { caseId?: string };
      if (data.caseId) ids.add(data.caseId);
    }
    return ids;
  }, [caseFrames, tick]);

  const rows = React.useMemo<CaseQueueRow[]>(() => {
    if (!casesQuery.data) return [];
    return buildQueueRows(
      casesQuery.data.content,
      disputesQuery.data?.content ?? [],
      transactionsQuery.data?.content ?? [],
    );
  }, [casesQuery.data, disputesQuery.data, transactionsQuery.data]);

  const lanes = React.useMemo(() => groupByLane(rows, CASE_STATUS_LANES), [rows]);

  const awaitingHuman = rows.filter((row) => row.awaitingHuman);
  const openRows = rows.filter((row) => row.status !== 'CLOSED');
  const exposure = laneExposure(openRows);
  const exposureEntries = [...exposure.entries()];

  const isLoading =
    casesQuery.isLoading || disputesQuery.isLoading || transactionsQuery.isLoading;

  const refetchAll = () => {
    void casesQuery.refetch();
    void disputesQuery.refetch();
    void transactionsQuery.refetch();
  };

  return (
    <div className="space-y-5">
      <PageHeader
        eyebrow="Defend"
        title="Case queue"
        description="Every dispute case in workflow order. A lane is a Temporal state, not a folder: a card only moves when the workflow moves it, or when a person unblocks it."
        meta={
          <>
            <Badge variant="outline">{merchantLabel}</Badge>
            {liveCaseIds.size > 0 ? (
              <Badge variant="primary" className="gap-1">
                <Radio className="size-3 animate-pulse-dot" aria-hidden />
                {liveCaseIds.size} just updated
              </Badge>
            ) : null}
          </>
        }
        actions={
          <Button variant="outline" size="sm" onClick={refetchAll} disabled={isLoading}>
            <RefreshCw className={cn('size-3.5', isLoading && 'animate-spin')} />
            Refresh
          </Button>
        }
      />

      {!merchantId ? (
        <EmptyState
          icon={Layers}
          title="Select a merchant"
          description="The case queue is merchant-scoped. Pick a merchant in the top bar to load its cases."
        />
      ) : casesQuery.isError ? (
        <ErrorState error={casesQuery.error} onRetry={refetchAll} />
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <StatTile
              label="Open cases"
              value={isLoading ? '-' : openRows.length}
              hint="Everything not in CLOSED."
              loading={isLoading}
            />
            <StatTile
              label="Waiting on a human"
              value={isLoading ? '-' : awaitingHuman.length}
              tone={awaitingHuman.length > 0 ? 'warning' : 'neutral'}
              hint="Parked on the humanDecision signal."
              loading={isLoading}
            />
            <StatTile
              label="Failed workflows"
              value={isLoading ? '-' : rows.filter((row) => row.failed).length}
              tone={rows.some((row) => row.failed) ? 'critical' : 'neutral'}
              hint="Non-retryable activity failure; needs an operator."
              loading={isLoading}
            />
            <StatTile
              label="Amount at risk"
              value={
                exposureEntries.length === 0 ? (
                  '-'
                ) : (
                  <MoneyDisplay
                    money={{
                      amountMinor: exposureEntries[0]?.[1] ?? 0,
                      currency: exposureEntries[0]?.[0] ?? 'USD',
                    }}
                    compact
                  />
                )
              }
              hint={
                exposureEntries.length > 1
                  ? `${exposureEntries.length} currencies; totals are never summed across them.`
                  : 'Sum of open disputed amounts.'
              }
              loading={isLoading}
            />
          </div>

          {isLoading && rows.length === 0 ? (
            <LoadingState variant="cards" count={4} />
          ) : rows.length === 0 ? (
            <EmptyState
              icon={Layers}
              title="No cases for this merchant"
              description="A case is opened by case-orchestrator-service when a DisputeCreated event lands. Inject one from the Simulation console to see the workflow run."
            />
          ) : (
            <div
              className="flex gap-3 overflow-x-auto scrollbar-thin pb-3"
              role="list"
              aria-label="Case queue swimlanes"
            >
              {CASE_STATUS_LANES.map((lane) => {
                const laneRows = lanes.get(lane) ?? [];
                return (
                  <section
                    key={lane}
                    role="listitem"
                    aria-label={`${lane} lane, ${laneRows.length} cases`}
                    className="flex w-[19rem] shrink-0 flex-col rounded-lg border border-border bg-background/40"
                  >
                    <header className="sticky top-0 space-y-1.5 rounded-t-lg border-b border-border bg-card px-3 py-2.5">
                      <div className="flex items-center justify-between gap-2">
                        <StatusBadge kind="case" value={lane} />
                        <span className="tabular text-xs text-muted-foreground">
                          {laneRows.length}
                        </span>
                      </div>
                      <p className="text-2xs leading-snug text-muted-foreground">
                        {LANE_DESCRIPTION[lane]}
                      </p>
                    </header>

                    <div className="flex flex-col gap-2 p-2">
                      {laneRows.length === 0 ? (
                        <p className="rounded-md border border-dashed border-border px-3 py-6 text-center text-2xs text-muted-foreground">
                          Empty
                        </p>
                      ) : (
                        laneRows.map((row) => (
                          <CaseCard
                            key={row.caseId}
                            row={row}
                            merchantLabel={merchantLabel}
                            live={liveCaseIds.has(row.caseId)}
                          />
                        ))
                      )}
                    </div>
                  </section>
                );
              })}
            </div>
          )}

          <p className="text-2xs text-muted-foreground">
            Readiness on a card is the transaction&apos;s latest snapshot. The snapshot captured
            when the dispute landed is on the case&apos;s X-Ray, which is the authoritative view.
          </p>
        </>
      )}
    </div>
  );
}
