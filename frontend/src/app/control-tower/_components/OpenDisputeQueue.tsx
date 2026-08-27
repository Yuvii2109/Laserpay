'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { PartyPopper } from 'lucide-react';
import {
  DeadlineCountdown,
  EmptyState,
  ErrorState,
  LoadingState,
  MoneyDisplay,
  StatusBadge,
} from '@/components/shared';
import { disputesApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum } from '@/lib/format/id';
import { isTerminalDispute, type DisputeView } from '@/lib/types/dispute';

export interface OpenDisputeQueueProps {
  merchantId: string;
  limit?: number;
  fetchSize?: number;
}

/** Nulls sort last: a dispute without a deadline is not urgent, it is unscheduled. */
function byDeadline(a: DisputeView, b: DisputeView): number {
  if (a.deadlineAt && b.deadlineAt) return a.deadlineAt.localeCompare(b.deadlineAt);
  if (a.deadlineAt) return -1;
  if (b.deadlineAt) return 1;
  return b.openedAt.localeCompare(a.openedAt);
}

/**
 * Open disputes, soonest deadline first.
 *
 * `GET /disputes` takes a single status, and "open" is nine of the ten statuses in contract 6,
 * so the queue asks for the merchant's disputes and drops the terminal ones client-side using
 * the same terminal set the Java `DisputeView.isTerminal()` declares.
 */
export function OpenDisputeQueue({ merchantId, limit = 8, fetchSize = 100 }: OpenDisputeQueueProps) {
  const query = React.useMemo(
    () => ({ merchantId, page: 0, size: fetchSize }),
    [merchantId, fetchSize],
  );

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: queryKeys.disputes.list(query),
    queryFn: ({ signal }) => disputesApi.list(query, signal),
    enabled: Boolean(merchantId),
  });

  const open = React.useMemo(
    () => (data?.content ?? []).filter((dispute) => !isTerminalDispute(dispute.status)).sort(byDeadline),
    [data],
  );

  if (isError) return <ErrorState error={error} onRetry={() => void refetch()} compact />;
  if (isLoading) return <LoadingState variant="rows" count={5} />;

  if (open.length === 0) {
    return (
      <EmptyState
        icon={PartyPopper}
        title="No open disputes"
        description="Every dispute for this merchant has reached a terminal status: won, lost, expired or withdrawn."
        compact
      />
    );
  }

  const rows = open.slice(0, limit);

  return (
    <div className="space-y-2">
      <ul className="divide-y divide-border">
        {rows.map((dispute) => (
          <li key={dispute.disputeId} className="flex flex-wrap items-center gap-x-3 gap-y-2 py-2.5 first:pt-0">
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                <Link
                  href={`/disputes/${dispute.disputeId}`}
                  className="mono-id text-sm text-foreground underline-offset-4 hover:underline"
                >
                  {dispute.disputeId}
                </Link>
                <StatusBadge kind="dispute" value={dispute.status} />
              </div>
              <p className="mt-0.5 truncate text-xs text-muted-foreground">
                {humanizeEnum(dispute.reasonCode)} ·{' '}
                <Link
                  href={`/transactions/${dispute.transactionId}`}
                  className="mono-id underline-offset-4 hover:text-foreground hover:underline"
                >
                  {dispute.transactionId}
                </Link>
              </p>
            </div>
            <div className="flex shrink-0 flex-col items-end gap-0.5">
              <MoneyDisplay money={dispute.amount} className="text-sm" />
              <DeadlineCountdown deadlineAt={dispute.deadlineAt} className="text-xs" />
            </div>
          </li>
        ))}
      </ul>

      <p className="text-xs text-muted-foreground">
        {open.length} open of {data?.totalElements ?? open.length} dispute
        {(data?.totalElements ?? open.length) === 1 ? '' : 's'} for this merchant.
      </p>
    </div>
  );
}
