'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { CalendarCheck2 } from 'lucide-react';
import {
  EmptyState,
  ErrorState,
  EvidenceTypeIcon,
  EVIDENCE_TYPE_LABEL,
  LoadingState,
  StatusBadge,
  TimestampDisplay,
} from '@/components/shared';
import { evidenceApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum } from '@/lib/format/id';
import { toneColorVar } from '@/lib/format/score';
import type { EvidenceView } from '@/lib/types/evidence';

export interface ExpiringEvidencePanelProps {
  merchantId: string;
  /** Horizon in days. Contract 7 penalises EXPIRING_SOON inside 7 days. */
  withinDays?: number;
  limit?: number;
  fetchSize?: number;
}

/**
 * Evidence about to stop counting.
 *
 * `EvidenceStatus.EXPIRING` is set by the platform against the merchant policy's
 * `expiringSoonDays`; this panel narrows that to the contract 7 penalty horizon (7 days by
 * default), because an artifact inside that window is already costing 5 readiness points per
 * mandatory requirement it was covering. Expired artifacts are shown too - they cost 10.
 */
export function ExpiringEvidencePanel({
  merchantId,
  withinDays = 7,
  limit = 8,
  fetchSize = 100,
}: ExpiringEvidencePanelProps) {
  const query = React.useMemo(
    () => ({ merchantId, status: 'EXPIRING' as const, page: 0, size: fetchSize }),
    [merchantId, fetchSize],
  );

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: queryKeys.evidence.list(query),
    queryFn: ({ signal }) => evidenceApi.search(query, signal),
    enabled: Boolean(merchantId),
  });

  // `now` is captured once per render pass so every row is measured against the same instant.
  const rows = React.useMemo<EvidenceView[]>(() => {
    const horizonMs = Date.now() + withinDays * 86_400_000;
    return (data?.content ?? [])
      .filter((item) => {
        if (!item.expiresAt) return false;
        const expiresMs = new Date(item.expiresAt).getTime();
        return Number.isFinite(expiresMs) && expiresMs <= horizonMs;
      })
      .sort((a, b) => (a.expiresAt ?? '').localeCompare(b.expiresAt ?? ''));
  }, [data, withinDays]);

  if (isError) return <ErrorState error={error} onRetry={() => void refetch()} compact />;
  if (isLoading) return <LoadingState variant="rows" count={5} />;

  if (rows.length === 0) {
    return (
      <EmptyState
        icon={CalendarCheck2}
        title={`Nothing expiring within ${withinDays} days`}
        description="No artifact marked EXPIRING falls inside the readiness penalty horizon. The nightly expiry sweep moves artifacts into this state as their policy max age approaches."
        compact
      />
    );
  }

  const visible = rows.slice(0, limit);
  const nowMs = Date.now();

  return (
    <div className="space-y-2">
      <ul className="divide-y divide-border">
        {visible.map((item) => {
          const expiresMs = item.expiresAt ? new Date(item.expiresAt).getTime() : Number.NaN;
          const daysLeft = Number.isFinite(expiresMs)
            ? Math.floor((expiresMs - nowMs) / 86_400_000)
            : null;
          const past = daysLeft !== null && daysLeft < 0;
          return (
            <li key={item.evidenceId} className="flex flex-wrap items-center gap-x-3 gap-y-2 py-2.5 first:pt-0">
              <EvidenceTypeIcon type={item.type} className="shrink-0" />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                  <Link
                    href={`/evidence/${item.evidenceId}`}
                    className="text-sm text-foreground underline-offset-4 hover:underline"
                  >
                    {EVIDENCE_TYPE_LABEL[item.type] ?? humanizeEnum(item.type)}
                  </Link>
                  <StatusBadge kind="evidence" value={item.status} />
                </div>
                <p className="mt-0.5 truncate text-xs text-muted-foreground">
                  <span className="mono-id">{item.evidenceId}</span> ·{' '}
                  <Link
                    href={`/transactions/${item.transactionId}`}
                    className="mono-id underline-offset-4 hover:text-foreground hover:underline"
                  >
                    {item.transactionId}
                  </Link>
                </p>
              </div>
              <div className="shrink-0 text-right">
                <span
                  className="block text-xs font-medium"
                  style={{ color: toneColorVar(past ? 'critical' : 'warning') }}
                >
                  {daysLeft === null
                    ? '-'
                    : past
                      ? `${Math.abs(daysLeft)}d overdue`
                      : daysLeft === 0
                        ? 'today'
                        : `${daysLeft}d left`}
                </span>
                <TimestampDisplay
                  value={item.expiresAt}
                  mode="absolute"
                  muted
                  className="block text-2xs"
                />
              </div>
            </li>
          );
        })}
      </ul>

      <p className="text-xs text-muted-foreground">
        {rows.length} artifact{rows.length === 1 ? '' : 's'} inside the {withinDays}-day horizon
        {data && data.totalElements > data.content.length
          ? ` (from the first ${data.content.length} of ${data.totalElements} marked expiring)`
          : ''}
        .
      </p>
    </div>
  );
}
