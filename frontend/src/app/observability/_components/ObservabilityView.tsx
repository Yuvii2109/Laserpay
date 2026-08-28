'use client';

import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Activity,
  BarChart3,
  Bot,
  ExternalLink,
  Gauge,
  Link2,
  RefreshCw,
  ShieldCheck,
  UserCheck,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/shared/ErrorState';
import { LoadingState } from '@/components/shared/LoadingState';
import { PageHeader } from '@/components/shared/PageHeader';
import { StatTile } from '@/components/shared/StatTile';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { auditApi, merchantsApi, metricsApi } from '@/lib/api/endpoints';
import { isApiError } from '@/lib/api/client';
import { queryKeys } from '@/lib/query/keys';
import { aiAdmissionRate, autoPrepareRate } from '@/lib/types/metrics';
import { useLiveStore } from '@/lib/store/liveStore';
import { rangeToFrom, useUiStore, TIME_RANGE_LABEL, type TimeRangePreset } from '@/lib/store/uiStore';
import { FunnelChart } from './FunnelChart';
import { ServiceHealthGrid } from './ServiceHealthGrid';
import { EXTERNAL_CONSOLES, HEADLINE_METRICS } from './services';

const RANGES: readonly TimeRangePreset[] = ['24h', '7d', '30d', '90d', 'all'];

function formatPercent(value: number, digits = 0): string {
  if (!Number.isFinite(value)) return '-';
  return `${(value * 100).toFixed(digits)}%`;
}

/**
 * Observability.
 *
 * The page exists to answer one commercial question - how much of this platform's work needs a
 * model, and how much needs a person - and one operational question: is anything down. It shows
 * the funnel first because the funnel is the argument.
 */
export function ObservabilityView() {
  const merchantId = useUiStore((state) => state.selectedMerchantId);
  const range = useUiStore((state) => state.filters.range) ?? '30d';
  const setFilter = useUiStore((state) => state.setFilter);
  const counts = useLiveStore((state) => state.counts);
  const duplicatesDropped = useLiveStore((state) => state.duplicatesDropped);

  const from = React.useMemo(() => rangeToFrom(range), [range]);

  const funnelQuery = useQuery({
    queryKey: queryKeys.metrics.funnel({
      merchantId: merchantId ?? undefined,
      ...(from ? { from } : {}),
    }),
    queryFn: ({ signal }) =>
      metricsApi.funnel({ merchantId: merchantId ?? undefined, ...(from ? { from } : {}) }, signal),
  });

  const readinessQuery = useQuery({
    queryKey: queryKeys.health.ready(),
    queryFn: ({ signal }) => merchantsApi.ready(signal),
    refetchInterval: 30_000,
    retry: false,
  });

  const chainQuery = useQuery({
    queryKey: queryKeys.audit.chain(merchantId ?? 'none'),
    queryFn: ({ signal }) => auditApi.verifyChain(merchantId as string, signal),
    enabled: Boolean(merchantId),
  });

  const funnel = funnelQuery.data;
  const admissionRate = funnel ? aiAdmissionRate(funnel) : 0;
  const reduction = funnel ? 1 - admissionRate : 0;
  const autoRate = funnel ? autoPrepareRate(funnel) : 0;
  const totalFrames = Object.values(counts).reduce((sum, value) => sum + value, 0);

  const gatewayReachable = !(
    readinessQuery.isError &&
    isApiError(readinessQuery.error) &&
    readinessQuery.error.status === 0
  );

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Verify"
        title="Observability"
        description="Where the work goes. Every event ingested is a candidate for cost: this page shows how few of them ever reach a model or a person, and where to look when that changes."
        meta={
          <>
            <Badge variant="outline">{merchantId ?? 'all merchants'}</Badge>
            <Badge variant="subtle">{TIME_RANGE_LABEL[range]}</Badge>
          </>
        }
        actions={
          <>
            <div className="flex flex-wrap items-center gap-1" role="group" aria-label="Time range">
              {RANGES.map((preset) => (
                <Button
                  key={preset}
                  size="sm"
                  variant={range === preset ? 'secondary' : 'ghost'}
                  onClick={() => setFilter('range', preset)}
                  aria-pressed={range === preset}
                >
                  {preset}
                </Button>
              ))}
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                void funnelQuery.refetch();
                void readinessQuery.refetch();
                void chainQuery.refetch();
              }}
              disabled={funnelQuery.isFetching}
            >
              <RefreshCw className={cn('size-3.5', funnelQuery.isFetching && 'animate-spin')} />
              Refresh
            </Button>
          </>
        }
      />

      {funnelQuery.isError ? (
        <ErrorState error={funnelQuery.error} onRetry={() => void funnelQuery.refetch()} />
      ) : funnelQuery.isLoading || !funnel ? (
        <LoadingState variant="cards" count={4} label="Loading funnel" />
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <StatTile
              label="AI invocation reduction"
              value={formatPercent(reduction, 1)}
              tone="good"
              icon={Bot}
              hint={`${funnel.aiInvestigated.toLocaleString('en-US')} of ${funnel.candidates.toLocaleString('en-US')} dispute candidates reached a model. The rest were resolved deterministically.`}
            />
            <StatTile
              label="Events ingested"
              value={funnel.events.toLocaleString('en-US')}
              icon={Activity}
              hint="Canonical events across every source in the window."
            />
            <StatTile
              label="Auto-prepared"
              value={formatPercent(autoRate, 1)}
              icon={ShieldCheck}
              hint={`${funnel.autoPrepared.toLocaleString('en-US')} candidates reached a representment with no human step.`}
            />
            <StatTile
              label="Escalated to a human"
              value={funnel.humanReviewed.toLocaleString('en-US')}
              tone={funnel.humanReviewed > 0 ? 'warning' : 'neutral'}
              icon={UserCheck}
              hint={`${funnel.denied.toLocaleString('en-US')} results were denied outright by the safety gate.`}
            />
          </div>

          <section className="surface-card p-4" aria-label="Funnel">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
                <BarChart3 className="size-4 text-muted-foreground" aria-hidden />
                Events to human
              </h2>
              <span className="text-2xs text-muted-foreground">
                <TimestampDisplay value={funnel.from} mode="absolute" className="text-2xs" /> →{' '}
                <TimestampDisplay value={funnel.to} mode="absolute" className="text-2xs" />
              </span>
            </div>

            <FunnelChart className="mt-4" metrics={funnel} />

            <div className="mt-4 rounded-md border border-primary/30 bg-primary/5 p-3">
              <p className="text-sm font-medium text-foreground">
                {formatPercent(reduction, 1)} of dispute candidates never reached a model.
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                Admission control (contract 9.4) admits a case only when the deterministic path is
                unresolved, the priority score clears 55 and the Redis budget allows it. Every case
                it turns away is one that <span className="mono-id">evidence-core</span> answered
                on its own - at no token cost, with a result that is reproducible and auditable.
              </p>
              <dl className="mt-2 grid grid-cols-2 gap-x-6 gap-y-1 text-2xs sm:grid-cols-4">
                <Ratio label="Candidates / events" value={formatPercent(funnel.events > 0 ? funnel.candidates / funnel.events : 0, 2)} />
                <Ratio label="Ambiguous / candidates" value={formatPercent(funnel.candidates > 0 ? funnel.ambiguous / funnel.candidates : 0, 1)} />
                <Ratio label="AI / ambiguous" value={formatPercent(funnel.ambiguous > 0 ? funnel.aiInvestigated / funnel.ambiguous : 0, 1)} />
                <Ratio label="Human / candidates" value={formatPercent(funnel.candidates > 0 ? funnel.humanReviewed / funnel.candidates : 0, 1)} />
              </dl>
            </div>
          </section>
        </>
      )}

      <section className="space-y-3" aria-label="Platform consoles">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <Link2 className="size-4 text-muted-foreground" aria-hidden />
          Platform consoles
        </h2>
        <div className="grid gap-3 sm:grid-cols-2">
          {EXTERNAL_CONSOLES.filter((item) => item.primary).map((item) => (
            <a
              key={item.label}
              href={item.url}
              target="_blank"
              rel="noreferrer"
              className="surface-card flex items-start justify-between gap-3 p-4 transition-colors hover:border-primary/50 hover:bg-accent/40"
            >
              <span className="min-w-0">
                <span className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
                  {item.label}
                  <ExternalLink className="size-3.5 text-muted-foreground" aria-hidden />
                </span>
                <span className="mono-id mt-0.5 block text-2xs text-muted-foreground">
                  {item.url}
                </span>
                <span className="mt-1 block text-xs text-muted-foreground">{item.detail}</span>
              </span>
            </a>
          ))}
        </div>
        <div className="flex flex-wrap gap-2">
          {EXTERNAL_CONSOLES.filter((item) => !item.primary).map((item) => (
            <a
              key={item.label}
              href={item.url}
              target="_blank"
              rel="noreferrer"
              title={item.detail}
              className="inline-flex items-center gap-1.5 rounded-md border border-border px-2.5 py-1.5 text-xs text-foreground transition-colors hover:bg-accent"
            >
              {item.label}
              <span className="mono-id text-2xs text-muted-foreground">
                {item.url.replace('http://localhost', ':')}
              </span>
              <ExternalLink className="size-3 text-muted-foreground" aria-hidden />
            </a>
          ))}
        </div>
      </section>

      <ServiceHealthGrid readiness={readinessQuery.data} gatewayReachable={gatewayReachable} />

      <div className="grid gap-4 lg:grid-cols-2">
        <section className="surface-card p-4" aria-label="Audit chain">
          <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
            <ShieldCheck className="size-4 text-muted-foreground" aria-hidden />
            Audit chain
          </h2>
          {!merchantId ? (
            <p className="mt-2 text-xs text-muted-foreground">
              Select a merchant to verify its hash chain.
            </p>
          ) : chainQuery.isLoading ? (
            <LoadingState variant="text" count={3} className="mt-3" label="Verifying chain" />
          ) : chainQuery.isError ? (
            <ErrorState
              className="mt-3"
              compact
              error={chainQuery.error}
              onRetry={() => void chainQuery.refetch()}
            />
          ) : chainQuery.data ? (
            <>
              <p
                className="mt-2 text-lg font-semibold"
                style={{
                  color: chainQuery.data.intact
                    ? 'var(--status-good)'
                    : 'var(--status-critical)',
                }}
              >
                {chainQuery.data.intact ? 'Chain intact' : 'Chain diverged'}
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                <span className="tabular">{chainQuery.data.eventsChecked.toLocaleString('en-US')}</span>{' '}
                audit events recomputed and compared, newest to oldest. Verified{' '}
                <TimestampDisplay value={chainQuery.data.verifiedAt} className="text-xs" />.
              </p>
              {chainQuery.data.firstDivergenceId ? (
                <p className="mono-id mt-1 text-xs" style={{ color: 'var(--status-critical)' }}>
                  First divergence: {chainQuery.data.firstDivergenceId}
                </p>
              ) : null}
              {chainQuery.data.detail ? (
                <p className="mt-1 text-xs text-muted-foreground">{chainQuery.data.detail}</p>
              ) : null}
            </>
          ) : null}
        </section>

        <section className="surface-card p-4" aria-label="This session">
          <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
            <Gauge className="size-4 text-muted-foreground" aria-hidden />
            This browser session
          </h2>
          <p className="mt-1 text-xs text-muted-foreground">
            Counted client-side from the control-tower socket. Not a platform metric - the
            authoritative counters are the <span className="mono-id">pdei_*</span> series in
            Prometheus.
          </p>
          <dl className="mt-3 grid grid-cols-2 gap-3">
            <SessionStat label="Frames received" value={totalFrames} />
            <SessionStat label="Duplicates dropped" value={duplicatesDropped} />
            <SessionStat label="Readiness updates" value={counts.READINESS_UPDATED} />
            <SessionStat label="Case updates" value={counts.CASE_UPDATED} />
            <SessionStat label="Gaps detected" value={counts.GAP_DETECTED} />
            <SessionStat label="Chaos injections" value={counts.CHAOS_INJECTED} />
          </dl>
        </section>
      </div>

      <section className="surface-card p-4" aria-label="Headline metrics">
        <h2 className="text-sm font-semibold text-foreground">Metrics worth a dashboard</h2>
        <p className="mt-1 text-xs text-muted-foreground">
          Contract 13 names these; every Spring service exposes them on{' '}
          <span className="mono-id">/actuator/prometheus</span> and the Python service on{' '}
          <span className="mono-id">/metrics</span>.
        </p>
        <ul className="mt-3 grid gap-x-6 gap-y-2 sm:grid-cols-2">
          {HEADLINE_METRICS.map((metric) => (
            <li key={metric.name} className="text-xs">
              <span className="mono-id text-foreground">{metric.name}</span>
              <span className="block text-2xs text-muted-foreground">{metric.meaning}</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

function Ratio({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="tabular font-medium text-foreground">{value}</dd>
    </div>
  );
}

function SessionStat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-2xs uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="tabular mt-0.5 text-lg font-semibold text-foreground">
        {value.toLocaleString('en-US')}
      </dd>
    </div>
  );
}
