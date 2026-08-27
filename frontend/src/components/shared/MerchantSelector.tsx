'use client';

import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Building2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { merchantsApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { useUiStore } from '@/lib/store/uiStore';

export interface MerchantSelectorProps {
  className?: string;
  /** Hide the leading icon in tight layouts. */
  compact?: boolean;
}

/**
 * Merchant scope for the whole console.
 *
 * Nearly every route in contract 8.1 is merchant-scoped, so the selection lives in `uiStore`
 * (persisted) rather than in each page. The first merchant is auto-selected once, so a fresh
 * browser lands on data instead of an empty state.
 */
export function MerchantSelector({ className, compact = false }: MerchantSelectorProps) {
  const selectedMerchantId = useUiStore((state) => state.selectedMerchantId);
  const setSelectedMerchantId = useUiStore((state) => state.setSelectedMerchantId);

  const { data, isLoading, isError } = useQuery({
    queryKey: queryKeys.merchants.list({ size: 100 }),
    queryFn: ({ signal }) => merchantsApi.list({ size: 100 }, signal),
    staleTime: 5 * 60_000,
  });

  const merchants = React.useMemo(() => data?.content ?? [], [data]);

  React.useEffect(() => {
    if (merchants.length === 0) return;
    const stillExists = merchants.some((item) => item.merchantId === selectedMerchantId);
    if (!selectedMerchantId || !stillExists) {
      setSelectedMerchantId(merchants[0]?.merchantId ?? null);
    }
  }, [merchants, selectedMerchantId, setSelectedMerchantId]);

  if (isLoading) {
    return <Skeleton className={cn('h-9 w-52', className)} />;
  }

  if (isError || merchants.length === 0) {
    return (
      <span
        className={cn(
          'inline-flex h-9 items-center gap-2 rounded-md border border-dashed border-border px-3 text-xs text-muted-foreground',
          className,
        )}
      >
        <Building2 className="size-3.5" aria-hidden />
        No merchants available
      </span>
    );
  }

  return (
    <Select
      value={selectedMerchantId ?? undefined}
      onValueChange={(value) => setSelectedMerchantId(value)}
    >
      <SelectTrigger className={cn('h-9 w-56', className)} aria-label="Merchant">
        <span className="flex min-w-0 items-center gap-2">
          {compact ? null : <Building2 className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />}
          <SelectValue placeholder="Select a merchant" />
        </span>
      </SelectTrigger>
      <SelectContent>
        {merchants.map((merchant) => (
          <SelectItem key={merchant.merchantId} value={merchant.merchantId}>
            <span className="flex flex-col">
              <span>{merchant.displayName}</span>
              <span className="text-xs text-muted-foreground">
                {merchant.merchantId} · {merchant.defaultCurrency} · {merchant.country}
              </span>
            </span>
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
