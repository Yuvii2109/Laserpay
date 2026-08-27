'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight, ChevronsUpDown } from 'lucide-react';
import { cn, compareValues } from '@/lib/utils';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { useUiStore } from '@/lib/store/uiStore';
import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';
import { LoadingState } from './LoadingState';
import type { SortDirection } from '@/lib/types/common';

export interface DataTableColumn<T> {
  /** Stable key; also the sort key. */
  id: string;
  header: React.ReactNode;
  cell: (row: T, index: number) => React.ReactNode;
  /** Supply to make the column sortable. Return a primitive. */
  sortValue?: (row: T) => string | number | boolean | null | undefined;
  align?: 'left' | 'right' | 'center';
  /** Any CSS width, e.g. `12rem`. */
  width?: string;
  className?: string;
  headerClassName?: string;
  /** Hide below `sm`; keeps dense tables readable on narrow screens. */
  hideBelowSm?: boolean;
}

export interface DataTablePagination {
  page: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  onSizeChange?: (size: number) => void;
}

export interface DataTableProps<T> {
  columns: DataTableColumn<T>[];
  rows: T[] | undefined;
  getRowId: (row: T) => string;
  isLoading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  /** Navigate on row click. Takes precedence over `onRowClick`. */
  rowHref?: (row: T) => string | null;
  onRowClick?: (row: T) => void;
  emptyTitle?: string;
  emptyDescription?: React.ReactNode;
  emptyAction?: React.ReactNode;
  /** Omit for an unpaginated table; supply for server-side paging. */
  pagination?: DataTablePagination;
  initialSort?: { columnId: string; direction: SortDirection };
  className?: string;
  /** Sticky header for long scrolling tables. */
  stickyHeader?: boolean;
  caption?: React.ReactNode;
}

const PAGE_SIZES = [10, 25, 50, 100] as const;

/**
 * Generic, column-config driven table.
 *
 * Sorting is client-side over the rows it was handed - correct for a page of server-sorted
 * results, and honest about it: sorting re-orders the current page only. Paging is delegated
 * to the caller (`pagination`), because every list route in contract 8.1 is server-paged.
 * Row density follows the operator's UI preference.
 */
export function DataTable<T>({
  columns,
  rows,
  getRowId,
  isLoading = false,
  error,
  onRetry,
  rowHref,
  onRowClick,
  emptyTitle = 'Nothing to show',
  emptyDescription,
  emptyAction,
  pagination,
  initialSort,
  className,
  stickyHeader = false,
  caption,
}: DataTableProps<T>) {
  const router = useRouter();
  const density = useUiStore((state) => state.density);
  const [sort, setSort] = React.useState<{ columnId: string; direction: SortDirection } | null>(
    initialSort ?? null,
  );

  const visibleColumns = columns;

  const sortedRows = React.useMemo(() => {
    if (!rows) return [];
    if (!sort) return rows;
    const column = visibleColumns.find((item) => item.id === sort.columnId);
    if (!column?.sortValue) return rows;
    const factor = sort.direction === 'asc' ? 1 : -1;
    return [...rows].sort(
      (a, b) => factor * compareValues(column.sortValue?.(a), column.sortValue?.(b)),
    );
  }, [rows, sort, visibleColumns]);

  const toggleSort = (column: DataTableColumn<T>) => {
    if (!column.sortValue) return;
    setSort((current) => {
      if (current?.columnId !== column.id) return { columnId: column.id, direction: 'asc' };
      if (current.direction === 'asc') return { columnId: column.id, direction: 'desc' };
      return null;
    });
  };

  if (error) {
    return <ErrorState error={error} onRetry={onRetry} className={className} />;
  }

  if (isLoading && (!rows || rows.length === 0)) {
    return <LoadingState variant="rows" count={6} className={className} />;
  }

  if (!isLoading && sortedRows.length === 0) {
    return (
      <EmptyState
        title={emptyTitle}
        description={emptyDescription}
        action={emptyAction}
        className={className}
      />
    );
  }

  const cellPadding = density === 'compact' ? 'py-1.5' : 'py-2.5';
  const interactive = Boolean(rowHref || onRowClick);

  const handleRowActivate = (row: T) => {
    const href = rowHref?.(row);
    if (href) {
      router.push(href);
      return;
    }
    onRowClick?.(row);
  };

  return (
    <div className={cn('space-y-3', className)}>
      <div className={cn('rounded-lg border border-border bg-card', isLoading && 'opacity-60')}>
        <Table>
          <TableHeader className={cn(stickyHeader && 'sticky top-0 z-10 bg-card')}>
            <TableRow>
              {visibleColumns.map((column) => {
                const active = sort?.columnId === column.id;
                const SortIcon = !active ? ChevronsUpDown : sort?.direction === 'asc' ? ArrowUp : ArrowDown;
                return (
                  <TableHead
                    key={column.id}
                    style={column.width ? { width: column.width } : undefined}
                    className={cn(
                      column.align === 'right' && 'text-right',
                      column.align === 'center' && 'text-center',
                      column.hideBelowSm && 'hidden sm:table-cell',
                      column.headerClassName,
                    )}
                    aria-sort={active ? (sort?.direction === 'asc' ? 'ascending' : 'descending') : 'none'}
                  >
                    {column.sortValue ? (
                      <button
                        type="button"
                        onClick={() => toggleSort(column)}
                        className={cn(
                          'inline-flex items-center gap-1 rounded-sm transition-colors hover:text-foreground',
                          active && 'text-foreground',
                          column.align === 'right' && 'flex-row-reverse',
                        )}
                      >
                        {column.header}
                        <SortIcon className="size-3" aria-hidden />
                      </button>
                    ) : (
                      column.header
                    )}
                  </TableHead>
                );
              })}
            </TableRow>
          </TableHeader>

          <TableBody>
            {sortedRows.map((row, index) => (
              <TableRow
                key={getRowId(row)}
                className={cn(interactive && 'cursor-pointer')}
                onClick={interactive ? () => handleRowActivate(row) : undefined}
                tabIndex={interactive ? 0 : undefined}
                onKeyDown={
                  interactive
                    ? (event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          handleRowActivate(row);
                        }
                      }
                    : undefined
                }
              >
                {visibleColumns.map((column) => (
                  <TableCell
                    key={column.id}
                    className={cn(
                      cellPadding,
                      column.align === 'right' && 'text-right',
                      column.align === 'center' && 'text-center',
                      column.hideBelowSm && 'hidden sm:table-cell',
                      column.className,
                    )}
                  >
                    {column.cell(row, index)}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {caption ? <div className="border-t border-border px-3 py-2 text-xs text-muted-foreground">{caption}</div> : null}
      </div>

      {pagination ? <Pagination pagination={pagination} rowsOnPage={sortedRows.length} /> : null}
    </div>
  );
}

function Pagination({
  pagination,
  rowsOnPage,
}: {
  pagination: DataTablePagination;
  rowsOnPage: number;
}) {
  const { page, size, total, onPageChange, onSizeChange } = pagination;
  const pageCount = size > 0 ? Math.max(1, Math.ceil(total / size)) : 1;
  const first = total === 0 ? 0 : page * size + 1;
  const last = page * size + rowsOnPage;

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-muted-foreground">
      <span className="tabular">
        {first}-{last} of {total}
      </span>

      <div className="flex items-center gap-2">
        {onSizeChange ? (
          <label className="flex items-center gap-1.5">
            <span>Rows</span>
            <select
              value={size}
              onChange={(event) => onSizeChange(Number(event.target.value))}
              className="h-7 rounded-md border border-input bg-card px-1.5 text-xs"
            >
              {PAGE_SIZES.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
        ) : null}

        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          disabled={page <= 0}
          onClick={() => onPageChange(page - 1)}
          aria-label="Previous page"
        >
          <ChevronLeft className="size-3.5" />
        </Button>
        <span className="tabular">
          {page + 1} / {pageCount}
        </span>
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          disabled={page + 1 >= pageCount}
          onClick={() => onPageChange(page + 1)}
          aria-label="Next page"
        >
          <ChevronRight className="size-3.5" />
        </Button>
      </div>
    </div>
  );
}
