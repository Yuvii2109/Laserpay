'use client';

import { FilterX } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';

export interface FilterBarProps {
  children: React.ReactNode;
  /** Rendered as a "Clear filters" button when at least one filter is active. */
  onClear?: () => void;
  /** Controls whether the clear action is offered. */
  active?: boolean;
  /** Right-aligned slot: result counts, extra actions. */
  trailing?: React.ReactNode;
  className?: string;
  /** Accessible name of the filter region. */
  label?: string;
}

/**
 * The filter row above a list.
 *
 * It is a `<search>` landmark rather than a bare div so keyboard and screen-reader users can
 * jump to it, and the clear action is always in the same place on every list route - the
 * cross-page filter bag in `uiStore` survives navigation, so a way out of it must be visible.
 */
export function FilterBar({
  children,
  onClear,
  active = false,
  trailing,
  className,
  label = 'Filters',
}: FilterBarProps) {
  return (
    <search
      aria-label={label}
      className={cn(
        'flex flex-wrap items-end justify-between gap-x-4 gap-y-3 rounded-lg border border-border bg-card p-3',
        className,
      )}
    >
      <div className="flex flex-wrap items-end gap-3">{children}</div>
      <div className="flex items-center gap-2">
        {trailing}
        {onClear ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={onClear}
            disabled={!active}
            aria-label="Clear all filters"
          >
            <FilterX className="size-3.5" aria-hidden />
            Clear
          </Button>
        ) : null}
      </div>
    </search>
  );
}
