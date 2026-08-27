'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { Building2, Receipt } from 'lucide-react';
import {
  CopyableId,
  DataTable,
  EmptyState,
  FilterBar,
  FilterSelect,
  MoneyDisplay,
  PageHeader,
  ReadinessMeter,
  SearchInput,
  TimestampDisplay,
  type DataTableColumn,
} from '@/components/shared';
import { Badge } from '@/components/ui/badge';
import { transactionsApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { BAND_LABEL } from '@/lib/format/score';
import { humanizeEnum } from '@/lib/format/id';
import {
  rangeToFrom,
  TIME_RANGE_LABEL,
  useUiStore,
  type TimeRangePreset,
} from '@/lib/store/uiStore';
import { READINESS_BANDS, type ReadinessBand } from '@/lib/types/readiness';
import type { TransactionQuery, TransactionView } from '@/lib/types/transaction';

export interface TransactionsViewProps {
  /** Deep-link default, e.g. `/transactions?band=AT_RISK` from the Control Tower. */
  initialBand?: ReadinessBand;
}

const BAND_OPTIONS = READINESS_BANDS.map((band) => ({ value: band, label: BAND_LABEL[band] }));

const RANGE_OPTIONS = (['24h', '7d', '30d', '90d', 'all'] as TimeRangePreset[]).map((preset) => ({
  value: preset,
  label: TIME_RANGE_LABEL[preset],
}));

/**
 * `/transactions` — every transaction with its readiness band (contract 14).
 *
 * The three filters map straight onto `GET /transactions?merchantId&band&from&to&q&page&size`,
 * and paging is server-side because the list route is paged. Sorting is client-side over the
 * page in hand and the table says so: the gateway has no `sort` parameter yet.
 */
export function TransactionsView({ initialBand }: TransactionsViewProps) {
  const merchantId = useUiStore((state) => state.selectedMerchantId);
  const filters = useUiStore((state) => state.filters);
  const setFilter = useUiStore((state) => state.setFilter);
  const resetFilters = useUiStore((state) => state.resetFilters);
  const pageSize = useUiStore((state) => state.pageSize);
  const setPageSize = useUiStore((state) => state.setPageSize);

  const [page, setPage] = React.useState(0);

  // Apply the deep-linked band exactly once; after that the operator owns the filter.
  const appliedInitial = React.useRef(false);
  React.useEffect(() => {
    if (appliedInitial.current || !initialBand) return;
    appliedInitial.current = true;
    setFilter('band', initialBand);
  }, [initialBand, setFilter]);

  const band = filters.band;
  const range = filters.range ?? '30d';
  const search = filters.search ?? '';

  /**
   * `from` is quantised to the top of the hour. `rangeToFrom` is relative to "now", and an
   * un-quantised value would change on every render, producing a new query key each time and
   * refetching forever.
   */
  const from = React.useMemo(() => {
    const raw = rangeToFrom(range);
    if (!raw) return undefined;
    const bound = new Date(raw);
    bound.setUTCMinutes(0, 0, 0);
    return bound.toISOString();
  }, [range]);

  const query = React.useMemo<TransactionQuery>(
    () => ({
      merchantId: merchantId ?? undefined,
      band,
      from,
      q: search || undefined,
      page,
      size: pageSize,
    }),
    [merchantId, band, from, search, page, pageSize],
  );

  React.useEffect(() => {
    setPage(0);
  }, [merchantId, band, from, search, pageSize]);

  const { data, isLoading, isFetching, isError, error, refetch } = useQuery({
    queryKey: queryKeys.transactions.list(query),
    queryFn: ({ signal }) => transactionsApi.list(query, signal),
    enabled: Boolean(merchantId),
    placeholderData: keepPreviousData,
  });

  const columns = React.useMemo<DataTableColumn<TransactionView>[]>(
    () => [
      {
        id: 'transactionId',
        header: 'Transaction',
        width: '15rem',
        sortValue: (row) => row.transactionId,
        cell: (row) => (
          <div className="min-w-0">
            <CopyableId id={row.transactionId} />
            {row.externalRef ? (
              <span className="block truncate text-xs text-muted-foreground" title={row.externalRef}>
                {row.externalRef}
              </span>
            ) : null}
          </div>
        ),
      },
      {
        id: 'customer',
        header: 'Customer',
        hideBelowSm: true,
        sortValue: (row) => row.customerId,
        cell: (row) =>
          row.customerId ? (
            <span className="mono-id text-xs">{row.customerId}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
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
        id: 'status',
        header: 'Status',
        hideBelowSm: true,
        sortValue: (row) => row.status,
        cell: (row) => <Badge variant="subtle">{humanizeEnum(row.status)}</Badge>,
      },
      {
        id: 'readiness',
        header: 'Readiness',
        width: '11rem',
        sortValue: (row) => row.readinessScore,
        cell: (row) => (
          <ReadinessMeter
            score={row.readinessScore}
            band={row.readinessBand}
            showThresholds={false}
          />
        ),
      },
      {
        id: 'evidence',
        header: 'Evidence',
        align: 'right',
        width: '6rem',
        hideBelowSm: true,
        sortValue: (row) => row.evidenceCount ?? 0,
        cell: (row) => <span className="tabular">{row.evidenceCount ?? 0}</span>,
      },
      {
        id: 'gaps',
        header: 'Gaps',
        align: 'right',
        width: '5rem',
        hideBelowSm: true,
        sortValue: (row) => row.openGapCount ?? 0,
        cell: (row) => (
          <span
            className="tabular"
            style={{ color: (row.openGapCount ?? 0) > 0 ? 'var(--status-serious)' : undefined }}
          >
            {row.openGapCount ?? 0}
          </span>
        ),
      },
      {
        id: 'occurredAt',
        header: 'Occurred',
        width: '9rem',
        sortValue: (row) => row.occurredAt,
        cell: (row) => <TimestampDisplay value={row.occurredAt} muted />,
      },
      {
        id: 'dispute',
        header: 'Dispute',
        width: '9rem',
        hideBelowSm: true,
        sortValue: (row) => row.disputeId ?? '',
        cell: (row) =>
          row.disputeId ? (
            <Link
              href={`/disputes/${row.disputeId}`}
              className="mono-id text-xs underline-offset-4 hover:underline"
              onClick={(event) => event.stopPropagation()}
            >
              {row.disputeId}
            </Link>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
      },
    ],
    [],
  );

  const filtersActive = Boolean(band || search || range !== '30d');

  if (!merchantId) {
    return (
      <>
        <PageHeader title="Transactions" description="Every transaction and its evidence readiness." />
        <EmptyState
          icon={Building2}
          title="No merchant selected"
          description="Pick a merchant in the top bar. GET /transactions is merchant-scoped."
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Transactions"
        description="Every transaction and its evidence readiness. Rows open the transaction detail: timeline, evidence, graph, gaps and the readiness breakdown."
        meta={
          data ? (
            <Badge variant="outline">
              {data.totalElements} result{data.totalElements === 1 ? '' : 's'}
            </Badge>
          ) : null
        }
      />

      <FilterBar
        label="Transaction filters"
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
        <SearchInput
          id="transaction-search"
          label="Search"
          value={search}
          onChange={(value) => setFilter('search', value || undefined)}
          placeholder="Transaction id, customer or external ref"
          className="w-full sm:w-80"
        />
        <FilterSelect
          id="transaction-band"
          label="Readiness band"
          value={band}
          onChange={(value) => setFilter('band', value as ReadinessBand | undefined)}
          options={BAND_OPTIONS}
          allLabel="All bands"
        />
        <FilterSelect
          id="transaction-range"
          label="Occurred within"
          value={range}
          onChange={(value) => setFilter('range', (value as TimeRangePreset | undefined) ?? 'all')}
          options={RANGE_OPTIONS}
          allLabel="All time"
        />
      </FilterBar>

      <DataTable
        columns={columns}
        rows={data?.content}
        getRowId={(row) => row.transactionId}
        isLoading={isLoading}
        error={isError ? error : undefined}
        onRetry={() => void refetch()}
        rowHref={(row) => `/transactions/${row.transactionId}`}
        initialSort={{ columnId: 'occurredAt', direction: 'desc' }}
        emptyTitle="No transactions match these filters"
        emptyDescription={
          <>
            Widen the time range or clear the readiness band. Transactions arrive through
            ingestion-service and become visible once state-builder-worker has projected them.
          </>
        }
        emptyAction={
          <Link href="/control-tower" className="text-sm underline underline-offset-4">
            Back to the Control Tower
          </Link>
        }
        caption={
          <span className="inline-flex items-center gap-1.5">
            <Receipt className="size-3" aria-hidden />
            Column sorting re-orders the current page only — GET /transactions has no sort
            parameter yet, so cross-page ordering stays server-defined (newest first).
          </span>
        }
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
