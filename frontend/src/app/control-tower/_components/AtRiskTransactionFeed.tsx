'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQueries, useQuery } from '@tanstack/react-query';
import { ShieldCheck } from 'lucide-react';
import {
  EmptyState,
  ErrorState,
  LoadingState,
  ReadinessBadge,
  StatusBadge,
  TimestampDisplay,
} from '@/components/shared';
import { gapsApi, transactionsApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum } from '@/lib/format/id';
import { SEVERITY_RANK } from '@/lib/format/score';
import type { GapSeverity, ReadinessBand } from '@/lib/types/readiness';

export interface AtRiskTransactionFeedProps {
  merchantId: string;
  /** Transactions shown. The gap page fetched behind it is larger so grouping is meaningful. */
  limit?: number;
  /** Gap page size requested from the gateway. */
  fetchSize?: number;
}

interface AtRiskRow {
  transactionId: string;
  worstSeverity: GapSeverity;
  gapCount: number;
  /** Distinct gap types on this transaction, worst-first, for the one-line reason. */
  reasons: string[];
  latestDetectedAt: string;
}

/**
 * The at-risk feed: `GET /gaps` rolled up per transaction.
 *
 * The gateway returns gaps, not transactions, because a gap is the actionable unit - but an
 * operator works a transaction at a time, so the page groups them. Ordering is worst severity
 * first, then most gaps, then most recently detected: the same order `GapDetector` implies and
 * the same order the readiness penalties in contract 7 punish.
 *
 * A `GET /gaps` row is a bare `core.model.ReadinessGap`: it carries no score or band. The band
 * badge therefore comes from `GET /transactions/{transactionId}/readiness`, fetched once per
 * *visible* row (at most `limit`) and shared with the transaction detail page through the same
 * query key, so opening a row costs nothing extra.
 */
export function AtRiskTransactionFeed({
  merchantId,
  limit = 8,
  fetchSize = 100,
}: AtRiskTransactionFeedProps) {
  const query = React.useMemo(
    () => ({ merchantId, page: 0, size: fetchSize }),
    [merchantId, fetchSize],
  );

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: queryKeys.gaps.list(query),
    queryFn: ({ signal }) => gapsApi.list(query, signal),
    enabled: Boolean(merchantId),
  });

  const rows = React.useMemo<AtRiskRow[]>(() => {
    const gaps = data?.content ?? [];
    const byTransaction = new Map<string, AtRiskRow>();

    for (const gap of gaps) {
      const existing = byTransaction.get(gap.transactionId);
      if (!existing) {
        byTransaction.set(gap.transactionId, {
          transactionId: gap.transactionId,
          worstSeverity: gap.severity,
          gapCount: 1,
          reasons: [humanizeEnum(gap.type)],
          latestDetectedAt: gap.detectedAt,
        });
        continue;
      }
      existing.gapCount += 1;
      if (SEVERITY_RANK[gap.severity] < SEVERITY_RANK[existing.worstSeverity]) {
        existing.worstSeverity = gap.severity;
      }
      const reason = humanizeEnum(gap.type);
      if (!existing.reasons.includes(reason)) existing.reasons.push(reason);
      if (gap.detectedAt > existing.latestDetectedAt) existing.latestDetectedAt = gap.detectedAt;
    }

    return [...byTransaction.values()]
      .sort(
        (a, b) =>
          SEVERITY_RANK[a.worstSeverity] - SEVERITY_RANK[b.worstSeverity] ||
          b.gapCount - a.gapCount ||
          b.latestDetectedAt.localeCompare(a.latestDetectedAt),
      )
      .slice(0, limit);
  }, [data, limit]);

  const readinessQueries = useQueries({
    queries: rows.map((row) => ({
      queryKey: queryKeys.transactions.readiness(row.transactionId),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        transactionsApi.readiness(row.transactionId, undefined, signal),
    })),
  });

  const readinessByTransaction = React.useMemo(() => {
    const map = new Map<string, { score: number; band: ReadinessBand }>();
    rows.forEach((row, index) => {
      const snapshot = readinessQueries[index]?.data;
      if (snapshot) map.set(row.transactionId, { score: snapshot.score, band: snapshot.band });
    });
    return map;
  }, [rows, readinessQueries]);

  if (isError) return <ErrorState error={error} onRetry={() => void refetch()} compact />;
  if (isLoading) return <LoadingState variant="rows" count={5} />;

  if (rows.length === 0) {
    return (
      <EmptyState
        icon={ShieldCheck}
        title="Nothing at risk"
        description="No open readiness gap on any transaction for this merchant. Every mandatory requirement in the applicable policy is currently satisfied."
        compact
      />
    );
  }

  const total = data?.totalElements ?? 0;
  const fetched = data?.content.length ?? 0;

  return (
    <div className="space-y-2">
      <ul className="divide-y divide-border">
        {rows.map((row) => {
          const readiness = readinessByTransaction.get(row.transactionId);
          return (
            <li key={row.transactionId} className="flex flex-wrap items-center gap-x-3 gap-y-2 py-2.5 first:pt-0">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                  <Link
                    href={`/transactions/${row.transactionId}`}
                    className="mono-id text-sm text-foreground underline-offset-4 hover:underline"
                  >
                    {row.transactionId}
                  </Link>
                  <StatusBadge kind="severity" value={row.worstSeverity} />
                </div>
                <p className="mt-0.5 truncate text-xs text-muted-foreground">
                  {row.gapCount} gap{row.gapCount === 1 ? '' : 's'} · {row.reasons.join(', ')} · last
                  detected <TimestampDisplay value={row.latestDetectedAt} />
                </p>
              </div>
              {readiness ? (
                <ReadinessBadge band={readiness.band} score={readiness.score} size="sm" />
              ) : null}
            </li>
          );
        })}
      </ul>

      <p className="text-xs text-muted-foreground">
        {total} open gap{total === 1 ? '' : 's'} across this merchant
        {total > fetched ? `; grouped from the first ${fetched} by severity` : ''}.
      </p>
    </div>
  );
}
