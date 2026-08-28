'use client';

import { useQuery } from '@tanstack/react-query';
import {
  AlertOctagon,
  Building2,
  CalendarClock,
  FileClock,
  Gauge,
  ListChecks,
  ShieldAlert,
} from 'lucide-react';
import {
  EmptyState,
  ErrorState,
  LiveEventTicker,
  LoadingState,
  PageHeader,
  ReadinessBadge,
  StatTile,
  TimestampDisplay,
} from '@/components/shared';
import { Badge } from '@/components/ui/badge';
import { merchantsApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { useUiStore } from '@/lib/store/uiStore';
import { formatScore } from '@/lib/format/score';
import { humanizeEnum } from '@/lib/format/id';
import { CASE_STATUSES } from '@/lib/types/case';
import { PanelCard } from './_components/PanelCard';
import { ReadinessDistributionChart } from './_components/ReadinessDistributionChart';
import { AtRiskTransactionFeed } from './_components/AtRiskTransactionFeed';
import { OpenDisputeQueue } from './_components/OpenDisputeQueue';
import { ExpiringEvidencePanel } from './_components/ExpiringEvidencePanel';

/**
 * `/control-tower` - the Merchant Control Tower (contract 14).
 *
 * One question above the fold: *is this merchant's evidence ready, and what is blocking it?*
 * Every figure comes from `GET /merchants/{id}/summary`, which is counts only - the gateway
 * deliberately aggregates no money, because one total across mixed currencies would be a lie
 * (contract 5 money rule).
 * Everything on the page is merchant-scoped through `uiStore.selectedMerchantId` and every
 * panel is fed by a TanStack Query key that `useInvalidateOnWsEvent` already invalidates when
 * a control-tower frame arrives - so the page is live without any panel subscribing to the
 * socket itself. The only component that reads the socket directly is the ticker, and it reads
 * the tail for display, never for state.
 */
export default function ControlTowerPage() {
  const merchantId = useUiStore((state) => state.selectedMerchantId);

  const summaryQuery = useQuery({
    queryKey: queryKeys.merchants.summary(merchantId ?? ''),
    queryFn: ({ signal }) => merchantsApi.summary(merchantId as string, signal),
    enabled: Boolean(merchantId),
  });

  const summary = summaryQuery.data;
  // The gateway hands back per-status maps, zero-filled; the tiles want their totals.
  const totalEvidence = sumCounts(summary?.evidenceByStatus);
  const totalCases = sumCounts(summary?.casesByStatus);
  const caseRows = CASE_STATUSES.map(
    (status) => [status, summary?.casesByStatus?.[status] ?? 0] as const,
  ).filter(([, count]) => count > 0);

  if (!merchantId) {
    return (
      <>
        <PageHeader
          title="Merchant Control Tower"
          description="Evidence readiness, the open dispute queue and the gaps that put a representment at risk."
        />
        <EmptyState
          icon={Building2}
          title="No merchant selected"
          description="Pick a merchant in the top bar. Every route in this console is merchant-scoped, and the control-tower socket subscribes per merchant."
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Merchant Control Tower"
        description="Evidence readiness, the open dispute queue and the gaps that put a representment at risk. Panels refresh from REST whenever a control-tower frame says something moved."
        meta={
          summary ? (
            <>
              <Badge variant="outline">{summary.displayName}</Badge>
              <ReadinessBadge
                band={summary.dominantBand}
                score={summary.averageReadinessScore}
              />
            </>
          ) : null
        }
        actions={
          summary ? (
            <span className="text-xs text-muted-foreground">
              Summary generated <TimestampDisplay value={summary.generatedAt} />
            </span>
          ) : null
        }
      />

      {summaryQuery.isError ? (
        <ErrorState
          error={summaryQuery.error}
          onRetry={() => void summaryQuery.refetch()}
          title="Could not load the merchant summary"
          className="mb-4"
        />
      ) : null}

      {/* ---- above the fold: five numbers that decide what gets worked next ---- */}
      {summaryQuery.isLoading && !summary ? (
        <LoadingState variant="cards" count={5} className="xl:grid-cols-5" />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
          <StatTile
            label="Average readiness"
            icon={Gauge}
            value={formatScore(summary?.averageReadinessScore)}
            hint={
              summary
                ? `${summary.transactions} scored transaction${summary.transactions === 1 ? '' : 's'} · ${summary.atRiskTransactions} at risk`
                : undefined
            }
            loading={summaryQuery.isLoading}
            footer={
              summary ? (
                <ReadinessBadge
                  band={summary.dominantBand}
                  score={summary.averageReadinessScore}
                  showScore={false}
                  size="sm"
                  className="self-start"
                />
              ) : null
            }
          />

          <StatTile
            label="At-risk transactions"
            icon={AlertOctagon}
            value={summary?.atRiskTransactions ?? '-'}
            hint={
              summary
                ? `In the AT_RISK or NOT_READY band, of ${summary.transactions} scored`
                : undefined
            }
            tone={summary && summary.atRiskTransactions > 0 ? 'serious' : undefined}
            loading={summaryQuery.isLoading}
          />

          <StatTile
            label="Open disputes"
            icon={ShieldAlert}
            value={summary?.openDisputes ?? '-'}
            hint={
              summary
                ? `${summary.blockingGaps} blocking gap${summary.blockingGaps === 1 ? '' : 's'} (HIGH or CRITICAL) unresolved`
                : undefined
            }
            loading={summaryQuery.isLoading}
          />

          <StatTile
            label="Cases awaiting review"
            icon={ListChecks}
            value={summary?.casesRequiringReview ?? '-'}
            hint={
              summary
                ? `Of ${totalCases} case${totalCases === 1 ? '' : 's'} on this merchant. Parked on the humanDecision signal (contract 10, 8).`
                : undefined
            }
            tone={summary && summary.casesRequiringReview > 0 ? 'warning' : undefined}
            loading={summaryQuery.isLoading}
          />

          <StatTile
            label="Evidence expiring"
            icon={FileClock}
            value={summary?.expiringEvidence ?? '-'}
            hint={
              summary
                ? `Of ${totalEvidence} artifact${totalEvidence === 1 ? '' : 's'}. Inside the policy expiring-soon horizon; contract 7 penalises the last 7 days.`
                : undefined
            }
            tone={summary && summary.expiringEvidence > 0 ? 'warning' : undefined}
            loading={summaryQuery.isLoading}
          />
        </div>
      )}

      {/* ---- the working surfaces ---- */}
      <div className="mt-4 grid min-w-0 gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="min-w-0 space-y-4">
          <PanelCard
            title="Readiness distribution"
            description="Transactions per readiness band (contract 6 thresholds: 90 / 75 / 50)."
            icon={Gauge}
            href="/transactions"
            hrefLabel="Browse transactions"
            meta={
              summary ? (
                <>
                  · as of <TimestampDisplay value={summary.generatedAt} />
                </>
              ) : null
            }
          >
            {summaryQuery.isLoading && !summary ? (
              <LoadingState variant="panel" />
            ) : (
              <ReadinessDistributionChart distribution={summary?.readinessDistribution} />
            )}
          </PanelCard>

          <PanelCard
            title="At-risk transactions"
            description="Open readiness gaps from GET /gaps, rolled up per transaction, worst severity first."
            icon={AlertOctagon}
            href="/transactions?band=AT_RISK"
            hrefLabel="See at-risk"
            meta={
              summary ? (
                <>
                  · {summary.blockingGaps} blocking gap
                  {summary.blockingGaps === 1 ? '' : 's'} (HIGH or CRITICAL)
                </>
              ) : null
            }
          >
            <AtRiskTransactionFeed merchantId={merchantId} />
          </PanelCard>

          <PanelCard
            title="Open dispute queue"
            description="Non-terminal disputes, soonest response deadline first."
            icon={ShieldAlert}
            href="/disputes"
          >
            <OpenDisputeQueue merchantId={merchantId} />
          </PanelCard>
        </div>

        <div className="min-w-0 space-y-4">
          <LiveEventTicker maxHeight="26rem" />

          <PanelCard
            title="Evidence expiring in 7 days"
            description="Artifacts about to stop satisfying a requirement."
            icon={CalendarClock}
            href="/evidence?status=EXPIRING"
            hrefLabel="Explore evidence"
          >
            <ExpiringEvidencePanel merchantId={merchantId} withinDays={7} />
          </PanelCard>

          <PanelCard
            title="Case queue"
            description="Where this merchant's dispute cases currently sit in the Temporal workflow."
            icon={ListChecks}
            href="/cases"
            hrefLabel="Open the queue"
          >
            {summary ? (
              caseRows.length > 0 ? (
                <dl className="grid grid-cols-2 gap-3 text-sm">
                  {caseRows.map(([status, count]) => (
                    <div key={status}>
                      <dt className="text-xs uppercase tracking-wide text-muted-foreground">
                        {humanizeEnum(status)}
                      </dt>
                      <dd className="text-lg font-semibold">{count}</dd>
                    </div>
                  ))}
                </dl>
              ) : (
                <p className="text-xs text-muted-foreground">
                  No case has been opened for this merchant yet. A case is created by
                  case-orchestrator-service when a dispute arrives on pdei.dispute.events.v1.
                </p>
              )
            ) : (
              <LoadingState variant="text" count={3} />
            )}
          </PanelCard>
        </div>
      </div>
    </>
  );
}

/** Sum of a zero-filled per-enum count map; 0 when the summary has not arrived. */
function sumCounts(counts: Record<string, number> | undefined): number {
  return counts ? Object.values(counts).reduce((sum, value) => sum + value, 0) : 0;
}
