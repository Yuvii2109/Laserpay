'use client';

import * as React from 'react';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { Building2 } from 'lucide-react';
import {
  CopyableId,
  DataTable,
  DeadlineCountdown,
  EmptyState,
  FilterBar,
  FilterSelect,
  MoneyDisplay,
  PageHeader,
  StatusBadge,
  TimestampDisplay,
  type DataTableColumn,
} from '@/components/shared';
import { Badge } from '@/components/ui/badge';
import { disputesApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum } from '@/lib/format/id';
import { useUiStore } from '@/lib/store/uiStore';
import {
  DISPUTE_REASON_CODES,
  DISPUTE_STATUSES,
  isTerminalDispute,
  type DisputeQuery,
  type DisputeReasonCode,
  type DisputeStatus,
  type DisputeView,
} from '@/lib/types/dispute';

export interface DisputesViewProps {
  initialStatus?: DisputeStatus;
  initialReasonCode?: DisputeReasonCode;
}

const STATUS_OPTIONS = DISPUTE_STATUSES.map((status) => ({
  value: status,
  label: humanizeEnum(status),
}));

const REASON_OPTIONS = DISPUTE_REASON_CODES.map((reasonCode) => ({
  value: reasonCode,
  label: humanizeEnum(reasonCode),
}));

/**
 * `/disputes` - the dispute list (contract 14).
 *
 * Ordered by deadline rather than by recency: the response window is the only clock that
 * matters here, and a dispute with 12 hours left outranks one opened this morning with three
 * weeks to run. Terminal disputes keep their row but lose their countdown - there is nothing
 * left to respond to.
 */
export function DisputesView({ initialStatus, initialReasonCode }: DisputesViewProps) {
  const merchantId = useUiStore((state) => state.selectedMerchantId);
  const filters = useUiStore((state) => state.filters);
  const setFilter = useUiStore((state) => state.setFilter);
  const resetFilters = useUiStore((state) => state.resetFilters);
  const pageSize = useUiStore((state) => state.pageSize);
  const setPageSize = useUiStore((state) => state.setPageSize);

  const [page, setPage] = React.useState(0);

  const appliedInitial = React.useRef(false);
  React.useEffect(() => {
    if (appliedInitial.current) return;
    appliedInitial.current = true;
    if (initialStatus) setFilter('disputeStatus', initialStatus);
    if (initialReasonCode) setFilter('reasonCode', initialReasonCode);
  }, [initialStatus, initialReasonCode, setFilter]);

  const status = filters.disputeStatus;
  const reasonCode = filters.reasonCode;

  const query = React.useMemo<DisputeQuery>(
    () => ({
      merchantId: merchantId ?? undefined,
      status,
      reasonCode,
      page,
      size: pageSize,
    }),
    [merchantId, status, reasonCode, page, pageSize],
  );

  React.useEffect(() => {
    setPage(0);
  }, [merchantId, status, reasonCode, pageSize]);

  const { data, isLoading, isFetching, isError, error, refetch } = useQuery({
    queryKey: queryKeys.disputes.list(query),
    queryFn: ({ signal }) => disputesApi.list(query, signal),
    enabled: Boolean(merchantId),
    placeholderData: keepPreviousData,
  });

  const columns = React.useMemo<DataTableColumn<DisputeView>[]>(
    () => [
      {
        id: 'disputeId',
        header: 'Dispute',
        width: '13rem',
        sortValue: (row) => row.disputeId,
        cell: (row) => (
          <div className="min-w-0">
            <CopyableId id={row.disputeId} />
            {row.networkCaseRef ? (
              <span className="block truncate text-xs text-muted-foreground">
                {row.networkCaseRef}
              </span>
            ) : null}
          </div>
        ),
      },
      {
        id: 'reasonCode',
        header: 'Reason code',
        width: '14rem',
        sortValue: (row) => row.reasonCode,
        cell: (row) => <span className="text-sm">{humanizeEnum(row.reasonCode)}</span>,
      },
      {
        id: 'status',
        header: 'Status',
        width: '13rem',
        sortValue: (row) => row.status,
        cell: (row) => <StatusBadge kind="dispute" value={row.status} />,
      },
      {
        id: 'amount',
        header: 'Amount',
        align: 'right',
        width: '9rem',
        sortValue: (row) => row.amount.amountMinor,
        cell: (row) => <MoneyDisplay money={row.amount} />,
      },
      {
        id: 'transaction',
        header: 'Transaction',
        width: '11rem',
        hideBelowSm: true,
        sortValue: (row) => row.transactionId,
        cell: (row) => (
          <span onClick={(event) => event.stopPropagation()}>
            <CopyableId id={row.transactionId} shorten />
          </span>
        ),
      },
      {
        id: 'openedAt',
        header: 'Opened',
        width: '9rem',
        hideBelowSm: true,
        sortValue: (row) => row.openedAt,
        cell: (row) => <TimestampDisplay value={row.openedAt} muted />,
      },
      {
        id: 'deadlineAt',
        header: 'Deadline',
        width: '11rem',
        sortValue: (row) => row.deadlineAt ?? '',
        cell: (row) =>
          isTerminalDispute(row.status) ? (
            <span className="text-xs text-muted-foreground">
              closed {row.closedAt ? <TimestampDisplay value={row.closedAt} /> : ''}
            </span>
          ) : (
            <DeadlineCountdown deadlineAt={row.deadlineAt} />
          ),
      },
    ],
    [],
  );

  const openCount = (data?.content ?? []).filter((item) => !isTerminalDispute(item.status)).length;
  const filtersActive = Boolean(status || reasonCode);

  if (!merchantId) {
    return (
      <>
        <PageHeader title="Disputes" description="Disputes by status, reason code and deadline." />
        <EmptyState
          icon={Building2}
          title="No merchant selected"
          description="Pick a merchant in the top bar. GET /disputes is merchant-scoped."
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Disputes"
        description="Every dispute raised against this merchant, with its response deadline. Rows open the dispute detail: the countdown, the linked transaction and case, and the evidence its reason code requires."
        meta={
          data ? (
            <>
              <Badge variant="outline">
                {data.totalElements} dispute{data.totalElements === 1 ? '' : 's'}
              </Badge>
              {openCount > 0 ? (
                <Badge variant="subtle">{openCount} open on this page</Badge>
              ) : null}
            </>
          ) : null
        }
      />

      <FilterBar
        label="Dispute filters"
        onClear={resetFilters}
        active={filtersActive}
        trailing={
          isFetching && !isLoading ? (
            <span className="text-xs text-muted-foreground" role="status">
              Refreshing…
            </span>
          ) : null
        }
        className="mb-4"
      >
        <FilterSelect
          id="dispute-status"
          label="Status"
          value={status}
          onChange={(value) => setFilter('disputeStatus', value as DisputeStatus | undefined)}
          options={STATUS_OPTIONS}
          allLabel="All statuses"
          triggerClassName="w-56"
        />
        <FilterSelect
          id="dispute-reason"
          label="Reason code"
          value={reasonCode}
          onChange={(value) => setFilter('reasonCode', value as DisputeReasonCode | undefined)}
          options={REASON_OPTIONS}
          allLabel="All reason codes"
          triggerClassName="w-60"
        />
      </FilterBar>

      <DataTable
        columns={columns}
        rows={data?.content}
        getRowId={(row) => row.disputeId}
        isLoading={isLoading}
        error={isError ? error : undefined}
        onRetry={() => void refetch()}
        rowHref={(row) => `/disputes/${row.disputeId}`}
        initialSort={{ columnId: 'deadlineAt', direction: 'asc' }}
        emptyTitle="No disputes match these filters"
        emptyDescription="Clear the status or reason code. Disputes arrive on pdei.dispute.events.v1 from the PSP adapter, or are injected through POST /disputes."
        caption="Sorted by deadline: the response window is the clock that matters. Terminal disputes show their close date instead of a countdown."
        pagination={{
          page,
          size: pageSize,
          total: data?.totalElements ?? 0,
          onPageChange: setPage,
          onSizeChange: (size) => setPageSize(size),
        }}
      />
    </>
  );
}
