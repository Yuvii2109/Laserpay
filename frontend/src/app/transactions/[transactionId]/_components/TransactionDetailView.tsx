'use client';

import * as React from 'react';
import Link from 'next/link';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  FileSearch,
  Gauge,
  History,
  Loader2,
  Network,
  RefreshCw,
  TriangleAlert,
} from 'lucide-react';
import {
  CopyableId,
  DetailList,
  EmptyState,
  ErrorState,
  EventTimeline,
  EvidenceCard,
  EvidenceGraphView,
  GapList,
  LoadingState,
  MoneyDisplay,
  PageHeader,
  ReadinessBadge,
  ReadinessBreakdown,
  ReadinessMeter,
  TimestampDisplay,
  type DetailItem,
} from '@/components/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { toast } from '@/components/ui/sonner';
import { transactionsApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum } from '@/lib/format/id';
import { formatSpan } from '@/lib/format/date';
import type { TimelineEntry } from '@/lib/types/events';
import { TransactionFactsPanel } from './TransactionFactsPanel';
import { ContradictionList } from './ContradictionList';

export interface TransactionDetailViewProps {
  transactionId: string;
}

type TabKey = 'timeline' | 'evidence' | 'graph' | 'gaps' | 'readiness';

/**
 * `/transactions/[transactionId]` (contract 14).
 *
 * Five views of one transaction, each answering a different question:
 *   timeline  - what happened, and when did we find out?
 *   evidence  - what do we hold, in which version, at which hash?
 *   graph     - how do those artifacts and entities connect?
 *   gaps      - what is missing, stale or contradictory?
 *   readiness - why is the score what it is?
 *
 * The graph is fetched lazily: it is the largest payload and most visits never open it.
 * Everything else loads up front because the tab labels carry counts.
 */
export function TransactionDetailView({ transactionId }: TransactionDetailViewProps) {
  const queryClient = useQueryClient();
  const [tab, setTab] = React.useState<TabKey>('timeline');
  const [graphRequested, setGraphRequested] = React.useState(false);

  const transactionQuery = useQuery({
    queryKey: queryKeys.transactions.detail(transactionId),
    queryFn: ({ signal }) => transactionsApi.get(transactionId, signal),
  });

  const timelineQuery = useQuery({
    queryKey: queryKeys.transactions.timeline(transactionId),
    queryFn: ({ signal }) => transactionsApi.timeline(transactionId, signal),
  });

  const evidenceQuery = useQuery({
    queryKey: queryKeys.transactions.evidence(transactionId),
    queryFn: ({ signal }) => transactionsApi.evidence(transactionId, signal),
  });

  const readinessQuery = useQuery({
    queryKey: queryKeys.transactions.readiness(transactionId),
    queryFn: ({ signal }) => transactionsApi.readiness(transactionId, undefined, signal),
  });

  const graphQuery = useQuery({
    queryKey: queryKeys.transactions.graph(transactionId),
    queryFn: ({ signal }) => transactionsApi.graph(transactionId, signal),
    enabled: graphRequested,
  });

  React.useEffect(() => {
    if (tab === 'graph') setGraphRequested(true);
  }, [tab]);

  // `GET /transactions/{id}` answers with TransactionDetailResponse: the row is nested under
  // `transaction`, with `facts` and the two counters beside it.
  const detail = transactionQuery.data;
  const transaction = detail?.transaction;
  const facts = detail?.facts;
  const snapshot = readinessQuery.data;
  const evidence = evidenceQuery.data;

  /**
   * `TimelineEntry.at` is the business instant. The observation instant lives beside the entity
   * it describes, so it is resolved here rather than guessed inside the timeline component.
   */
  const observedAtByAggregate = React.useMemo(() => {
    const map = new Map<string, string>();
    for (const item of evidence ?? []) map.set(item.evidenceId, item.observedAt);
    if (transaction) map.set(transaction.transactionId, transaction.observedAt);
    return map;
  }, [evidence, transaction]);

  const observedAtFor = React.useCallback(
    (entry: TimelineEntry): string | null => {
      const fromDetails = entry.details?.['observedAt'];
      if (typeof fromDetails === 'string') return fromDetails;
      return observedAtByAggregate.get(entry.aggregateId) ?? null;
    },
    [observedAtByAggregate],
  );

  const recompute = useMutation({
    mutationFn: () => transactionsApi.recomputeReadiness(transactionId),
    onSuccess: (next) => {
      // The response IS the new snapshot; seed it, then invalidate what it can have moved.
      queryClient.setQueryData(queryKeys.transactions.readiness(transactionId), next);
      void queryClient.invalidateQueries({ queryKey: queryKeys.transactions.detail(transactionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.gaps.all() });
      if (transaction) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.merchants.summary(transaction.merchantId),
        });
      }
      toast.success(`Readiness recomputed: ${next.score} (${humanizeEnum(next.band)})`);
    },
    onError: (cause) => {
      toast.error(cause instanceof Error ? cause.message : 'Recomputation failed');
    },
  });

  if (transactionQuery.isLoading) {
    return (
      <div aria-busy="true">
        <LoadingState variant="panel" />
        <LoadingState variant="rows" count={8} className="mt-6" />
      </div>
    );
  }

  if (transactionQuery.isError || !detail || !transaction) {
    return (
      <>
        <PageHeader
          eyebrow={<BackLink />}
          title={transactionId}
          description="Transaction detail."
        />
        <ErrorState
          error={transactionQuery.error}
          onRetry={() => void transactionQuery.refetch()}
          title="Could not load this transaction"
        />
      </>
    );
  }

  const gapCount = snapshot?.gaps.length ?? detail.openGapCount;
  const contradictionCount = snapshot?.contradictions.length ?? 0;
  const evidenceCount = evidence?.length ?? detail.evidenceCount;
  const timelineCount = timelineQuery.data?.length ?? 0;

  const summaryItems: DetailItem[] = [
    {
      label: 'Amount',
      value: <MoneyDisplay money={transaction.amount} className="text-base font-semibold" />,
      hint:
        transaction.capturedAmount || transaction.refundedAmount ? (
          <>
            captured <MoneyDisplay money={transaction.capturedAmount} muted /> · refunded{' '}
            <MoneyDisplay money={transaction.refundedAmount} muted />
          </>
        ) : undefined,
    },
    { label: 'Status', value: <Badge variant="subtle">{humanizeEnum(transaction.status)}</Badge> },
    {
      label: 'Customer',
      value: transaction.customerId ? (
        <span className="mono-id">{transaction.customerId}</span>
      ) : (
        <span className="text-muted-foreground">Unknown</span>
      ),
      hint: transaction.channel ? humanizeEnum(transaction.channel) : undefined,
    },
    {
      label: 'Occurred',
      value: <TimestampDisplay value={transaction.occurredAt} mode="absolute" />,
      hint: (
        <>
          observed <TimestampDisplay value={transaction.observedAt} /> ·{' '}
          {formatSpan(transaction.occurredAt, transaction.observedAt)} ingest lag
        </>
      ),
    },
    {
      label: 'Merchant',
      value: <CopyableId id={transaction.merchantId} link={false} />,
      hint: transaction.externalRef ? `External ref ${transaction.externalRef}` : undefined,
    },
    {
      label: 'Dispute',
      value: transaction.disputeId ? (
        <Link
          href={`/disputes/${transaction.disputeId}`}
          className="mono-id underline-offset-4 hover:underline"
        >
          {transaction.disputeId}
        </Link>
      ) : (
        <span className="text-muted-foreground">None open</span>
      ),
      hint: transaction.lastEventAt ? (
        <>
          last event <TimestampDisplay value={transaction.lastEventAt} />
        </>
      ) : undefined,
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow={<BackLink />}
        title={transaction.transactionId}
        description="One transaction, five ways: what happened, what we hold, how it connects, what is missing, and why the score is what it is."
        meta={
          <>
            <ReadinessBadge
              band={snapshot?.band ?? transaction.readinessBand}
              score={snapshot?.score ?? transaction.readinessScore}
            />
            <Badge variant="outline">{humanizeEnum(transaction.status)}</Badge>
          </>
        }
        actions={
          <Button
            onClick={() => recompute.mutate()}
            disabled={recompute.isPending}
            variant="outline"
          >
            {recompute.isPending ? (
              <Loader2 className="size-4 animate-spin" aria-hidden />
            ) : (
              <RefreshCw className="size-4" aria-hidden />
            )}
            Recompute readiness
          </Button>
        }
      />

      {/* ---- header: summary + meter ---- */}
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Card className="p-5">
          <DetailList items={summaryItems} />
        </Card>

        <Card className="flex flex-col gap-4 p-5">
          <ReadinessMeter
            score={snapshot?.score ?? transaction.readinessScore}
            band={snapshot?.band ?? transaction.readinessBand}
            variant="hero"
            caption={
              snapshot
                ? undefined
                : 'No readiness snapshot yet - readiness-worker has not scored this transaction.'
            }
          />
          {snapshot ? (
            <dl className="space-y-1.5 text-xs">
              <div className="flex justify-between gap-3">
                <dt className="text-muted-foreground">Base score</dt>
                <dd className="tabular">{Math.round(snapshot.baseScore)}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted-foreground">Penalties</dt>
                <dd
                  className="tabular"
                  style={{
                    color: snapshot.penaltyPoints > 0 ? 'var(--status-critical)' : undefined,
                  }}
                >
                  {snapshot.penaltyPoints > 0 ? `−${Math.round(snapshot.penaltyPoints)}` : '0'}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted-foreground">Computed</dt>
                <dd>
                  <TimestampDisplay value={snapshot.computedAt} />
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted-foreground">Reason code</dt>
                <dd>{snapshot.reasonCode ? humanizeEnum(snapshot.reasonCode) : 'Baseline'}</dd>
              </div>
            </dl>
          ) : null}
          <Button
            variant="ghost"
            size="sm"
            className="self-start"
            onClick={() => setTab('readiness')}
          >
            <Gauge className="size-3.5" aria-hidden />
            See the breakdown
          </Button>
        </Card>
      </div>

      <TransactionFactsPanel facts={facts} />

      {/* ---- the five tabs ---- */}
      <Tabs value={tab} onValueChange={(value) => setTab(value as TabKey)} className="mt-5">
        <TabsList aria-label="Transaction views">
          <TabsTrigger value="timeline">
            <History className="size-3.5" aria-hidden />
            Timeline
            <TabCount value={timelineCount} />
          </TabsTrigger>
          <TabsTrigger value="evidence">
            <FileSearch className="size-3.5" aria-hidden />
            Evidence
            <TabCount value={evidenceCount} />
          </TabsTrigger>
          <TabsTrigger value="graph">
            <Network className="size-3.5" aria-hidden />
            Graph
          </TabsTrigger>
          <TabsTrigger value="gaps">
            <TriangleAlert className="size-3.5" aria-hidden />
            Gaps
            <TabCount value={gapCount + contradictionCount} tone={gapCount + contradictionCount > 0} />
          </TabsTrigger>
          <TabsTrigger value="readiness">
            <Gauge className="size-3.5" aria-hidden />
            Readiness
          </TabsTrigger>
        </TabsList>

        <TabsContent value="timeline">
          {timelineQuery.isLoading ? (
            <LoadingState variant="rows" count={6} />
          ) : timelineQuery.isError ? (
            <ErrorState error={timelineQuery.error} onRetry={() => void timelineQuery.refetch()} />
          ) : (
            <EventTimeline entries={timelineQuery.data} observedAtFor={observedAtFor} />
          )}
        </TabsContent>

        <TabsContent value="evidence">
          {evidenceQuery.isLoading ? (
            <LoadingState variant="cards" count={6} />
          ) : evidenceQuery.isError ? (
            <ErrorState error={evidenceQuery.error} onRetry={() => void evidenceQuery.refetch()} />
          ) : (evidence?.length ?? 0) === 0 ? (
            <EmptyState
              icon={FileSearch}
              title="No evidence on this transaction"
              description="Nothing has been captured or uploaded yet. Contract 9.4 treats zero evidence as a deterministic short-circuit: the platform recommends accepting liability rather than sending the case to a model."
            />
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {evidence?.map((item) => <EvidenceCard key={item.evidenceId} evidence={item} />)}
            </div>
          )}
        </TabsContent>

        <TabsContent value="graph">
          {graphQuery.isLoading ? (
            <LoadingState variant="panel" />
          ) : graphQuery.isError ? (
            <ErrorState error={graphQuery.error} onRetry={() => void graphQuery.refetch()} />
          ) : (
            <EvidenceGraphView graph={graphQuery.data} />
          )}
        </TabsContent>

        <TabsContent value="gaps" className="space-y-6">
          {readinessQuery.isLoading ? (
            <LoadingState variant="rows" count={4} />
          ) : readinessQuery.isError ? (
            <ErrorState error={readinessQuery.error} onRetry={() => void readinessQuery.refetch()} />
          ) : (
            <>
              <section aria-label="Readiness gaps">
                <h2 className="pb-2 text-sm font-semibold tracking-tight">
                  Gaps
                  <span className="ml-2 text-xs font-normal text-muted-foreground">
                    missing, expired, expiring or unverifiable
                  </span>
                </h2>
                <GapList gaps={snapshot?.gaps} />
              </section>

              <section aria-label="Contradictions">
                <h2 className="pb-2 text-sm font-semibold tracking-tight">
                  Contradictions
                  <span className="ml-2 text-xs font-normal text-muted-foreground">
                    artifacts that disagree with each other
                  </span>
                </h2>
                <ContradictionList contradictions={snapshot?.contradictions} />
              </section>
            </>
          )}
        </TabsContent>

        <TabsContent value="readiness">
          {readinessQuery.isLoading ? (
            <LoadingState variant="panel" />
          ) : readinessQuery.isError ? (
            <ErrorState error={readinessQuery.error} onRetry={() => void readinessQuery.refetch()} />
          ) : snapshot ? (
            <ReadinessBreakdown snapshot={snapshot} />
          ) : (
            <EmptyState
              icon={Gauge}
              title="Not scored yet"
              description="readiness-worker has not produced a snapshot for this transaction. Recomputing forces one now."
              action={
                <Button onClick={() => recompute.mutate()} disabled={recompute.isPending}>
                  <RefreshCw className="size-4" aria-hidden />
                  Recompute readiness
                </Button>
              }
            />
          )}
        </TabsContent>
      </Tabs>
    </>
  );
}

function BackLink() {
  return (
    <Link
      href="/transactions"
      className="inline-flex items-center gap-1 underline-offset-4 hover:text-foreground hover:underline"
    >
      <ArrowLeft className="size-3" aria-hidden />
      All transactions
    </Link>
  );
}

function TabCount({ value, tone = false }: { value: number; tone?: boolean }) {
  if (!Number.isFinite(value) || value <= 0) return null;
  return (
    <span
      className="tabular ml-0.5 rounded-full bg-muted px-1.5 text-2xs"
      style={tone ? { color: 'var(--status-serious)' } : undefined}
    >
      {value}
    </span>
  );
}
