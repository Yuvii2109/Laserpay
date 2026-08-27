'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { Building2 } from 'lucide-react';
import {
  CopyableId,
  DataTable,
  EmptyState,
  EvidenceTypeIcon,
  EVIDENCE_TYPE_LABEL,
  FilterBar,
  HashDisplay,
  PageHeader,
  SearchInput,
  StatusBadge,
  TimestampDisplay,
  type DataTableColumn,
} from '@/components/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { evidenceApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum } from '@/lib/format/id';
import { formatRatio } from '@/lib/format/money';
import { useUiStore } from '@/lib/store/uiStore';
import {
  EVIDENCE_STATUSES,
  EVIDENCE_TYPES,
  type EvidenceSearchQuery,
  type EvidenceStatus,
  type EvidenceType,
  type EvidenceView,
} from '@/lib/types/evidence';
import { FacetRail } from './FacetRail';

export interface EvidenceExplorerViewProps {
  initialQuery?: string;
  initialType?: EvidenceType;
  initialStatus?: EvidenceStatus;
}

const TYPE_OPTIONS = EVIDENCE_TYPES.map((type) => ({
  value: type,
  label: EVIDENCE_TYPE_LABEL[type],
  icon: <EvidenceTypeIcon type={type} className="size-3.5" />,
}));

const STATUS_OPTIONS = EVIDENCE_STATUSES.map((status) => ({
  value: status,
  label: humanizeEnum(status),
}));

/**
 * `/evidence` — the evidence explorer (contract 14).
 *
 * The search box is the Postgres full-text index behind `GET /evidence?q=`: the gateway turns
 * the string into a `tsquery`, so this side sends the raw text and never tries to build query
 * syntax. Type and status are facets rather than a filter menu because the point of an explorer
 * is to see the vocabulary, not to guess it.
 */
export function EvidenceExplorerView({
  initialQuery,
  initialType,
  initialStatus,
}: EvidenceExplorerViewProps) {
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
    if (initialQuery) setFilter('search', initialQuery);
    if (initialType) setFilter('evidenceType', initialType);
    if (initialStatus) setFilter('evidenceStatus', initialStatus);
  }, [initialQuery, initialType, initialStatus, setFilter]);

  const search = filters.search ?? '';
  const type = filters.evidenceType;
  const status = filters.evidenceStatus;

  const query = React.useMemo<EvidenceSearchQuery>(
    () => ({
      merchantId: merchantId ?? undefined,
      q: search || undefined,
      type,
      status,
      page,
      size: pageSize,
    }),
    [merchantId, search, type, status, page, pageSize],
  );

  React.useEffect(() => {
    setPage(0);
  }, [merchantId, search, type, status, pageSize]);

  const { data, isLoading, isFetching, isError, error, refetch } = useQuery({
    queryKey: queryKeys.evidence.list(query),
    queryFn: ({ signal }) => evidenceApi.search(query, signal),
    enabled: Boolean(merchantId),
    placeholderData: keepPreviousData,
  });

  const columns = React.useMemo<DataTableColumn<EvidenceView>[]>(
    () => [
      {
        id: 'type',
        header: 'Type',
        width: '14rem',
        sortValue: (row) => row.type,
        cell: (row) => (
          <div className="flex min-w-0 items-start gap-2">
            <EvidenceTypeIcon type={row.type} className="mt-0.5 shrink-0" />
            <div className="min-w-0">
              <span className="block truncate text-sm text-foreground">
                {EVIDENCE_TYPE_LABEL[row.type] ?? humanizeEnum(row.type)}
              </span>
              <span className="mono-id block truncate text-xs text-muted-foreground">
                {row.evidenceId}
              </span>
            </div>
          </div>
        ),
      },
      {
        id: 'summary',
        header: 'Summary',
        sortValue: (row) => row.summary ?? '',
        cell: (row) =>
          row.summary ? (
            <span className="line-clamp-2 text-sm text-muted-foreground">{row.summary}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
      },
      {
        id: 'status',
        header: 'Status',
        width: '9rem',
        sortValue: (row) => row.status,
        cell: (row) => <StatusBadge kind="evidence" value={row.status} />,
      },
      {
        id: 'version',
        header: 'Ver.',
        align: 'right',
        width: '4.5rem',
        sortValue: (row) => row.version,
        cell: (row) => <span className="tabular">v{row.version}</span>,
      },
      {
        id: 'sha256',
        header: 'sha256',
        width: '11rem',
        hideBelowSm: true,
        sortValue: (row) => row.sha256,
        cell: (row) => (
          <span onClick={(event) => event.stopPropagation()}>
            <HashDisplay sha256={row.sha256} label={`sha256 of ${row.evidenceId}`} />
          </span>
        ),
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
        id: 'source',
        header: 'Source',
        width: '9rem',
        hideBelowSm: true,
        sortValue: (row) => row.source,
        cell: (row) => (
          <span className="text-xs text-muted-foreground">{humanizeEnum(row.source)}</span>
        ),
      },
      {
        id: 'quality',
        header: 'Quality',
        align: 'right',
        width: '5.5rem',
        hideBelowSm: true,
        sortValue: (row) => row.qualityScore,
        cell: (row) => <span className="tabular text-xs">{formatRatio(row.qualityScore)}</span>,
      },
      {
        id: 'createdAt',
        header: 'Captured',
        width: '9rem',
        sortValue: (row) => row.createdAt,
        cell: (row) => <TimestampDisplay value={row.createdAt} muted />,
      },
      {
        id: 'expiresAt',
        header: 'Expires',
        width: '9rem',
        hideBelowSm: true,
        sortValue: (row) => row.expiresAt ?? '',
        cell: (row) =>
          row.expiresAt ? (
            <TimestampDisplay value={row.expiresAt} muted />
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
      },
    ],
    [],
  );

  const filtersActive = Boolean(search || type || status);

  if (!merchantId) {
    return (
      <>
        <PageHeader title="Evidence" description="Full-text search across every stored artifact." />
        <EmptyState
          icon={Building2}
          title="No merchant selected"
          description="Pick a merchant in the top bar. GET /evidence is merchant-scoped."
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Evidence"
        description="Full-text search across every stored artifact, with its type, status, version and digest. Rows open the artifact's provenance, version history and lineage."
        meta={
          data ? (
            <Badge variant="outline">
              {data.totalElements} artifact{data.totalElements === 1 ? '' : 's'}
            </Badge>
          ) : null
        }
      />

      <FilterBar
        label="Evidence search and facets"
        onClear={resetFilters}
        active={filtersActive}
        trailing={
          isFetching && !isLoading ? (
            <span className="text-xs text-muted-foreground" role="status">
              Searching…
            </span>
          ) : null
        }
        className="mb-4 flex-col items-stretch"
      >
        <div className="flex w-full flex-col gap-4">
          <SearchInput
            id="evidence-search"
            label="Full-text search"
            value={search}
            onChange={(value) => setFilter('search', value || undefined)}
            placeholder="Filename, summary, evidence id or type"
            className="w-full sm:max-w-xl"
            hint="Matched server-side against the tsvector columns added by V10__fts.sql."
          />
          <div className="grid gap-4 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
            <FacetRail
              label="Evidence type"
              value={type}
              onChange={(value) => setFilter('evidenceType', value as EvidenceType | undefined)}
              options={TYPE_OPTIONS}
              allLabel="Any type"
            />
            <FacetRail
              label="Status"
              value={status}
              onChange={(value) => setFilter('evidenceStatus', value as EvidenceStatus | undefined)}
              options={STATUS_OPTIONS}
              allLabel="Any status"
            />
          </div>
        </div>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={data?.content}
        getRowId={(row) => row.evidenceId}
        isLoading={isLoading}
        error={isError ? error : undefined}
        onRetry={() => void refetch()}
        rowHref={(row) => `/evidence/${row.evidenceId}`}
        initialSort={{ columnId: 'createdAt', direction: 'desc' }}
        emptyTitle="No evidence matches this search"
        emptyDescription="Clear a facet or widen the search text. Evidence is created by state-builder-worker from canonical events and by document-processor-service from uploads."
        emptyAction={
          <Button variant="outline" onClick={resetFilters}>
            Clear facets
          </Button>
        }
        caption={
          <>
            Facets are single-select and have no counts: the gateway exposes no aggregation
            endpoint, and a count taken from the visible page would misstate the corpus.
          </>
        }
        pagination={{
          page,
          size: pageSize,
          total: data?.totalElements ?? 0,
          onPageChange: setPage,
          onSizeChange: (size) => setPageSize(size),
        }}
      />

      <p className="mt-3 text-xs text-muted-foreground">
        Looking for a specific transaction&apos;s artifacts? Open it from{' '}
        <Link href="/transactions" className="underline underline-offset-4">
          Transactions
        </Link>{' '}
        and use its Evidence tab.
      </p>
    </>
  );
}
