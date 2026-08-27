import { cn } from '@/lib/utils';
import { Skeleton } from '@/components/ui/skeleton';

export interface LoadingStateProps {
  /** `rows` mimics a table, `cards` a KPI row, `panel` a single block. */
  variant?: 'rows' | 'cards' | 'panel' | 'text';
  count?: number;
  className?: string;
  label?: string;
}

/** Skeletons shaped like the content they replace, so nothing jumps when data lands. */
export function LoadingState({
  variant = 'rows',
  count = 5,
  className,
  label = 'Loading',
}: LoadingStateProps) {
  const items = Array.from({ length: count }, (_, index) => index);

  if (variant === 'cards') {
    return (
      <div
        className={cn('grid gap-3 sm:grid-cols-2 xl:grid-cols-4', className)}
        role="status"
        aria-label={label}
      >
        {items.map((index) => (
          <Skeleton key={index} className="h-28 w-full" />
        ))}
      </div>
    );
  }

  if (variant === 'panel') {
    return (
      <div className={cn('space-y-3', className)} role="status" aria-label={label}>
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  if (variant === 'text') {
    return (
      <div className={cn('space-y-2', className)} role="status" aria-label={label}>
        {items.map((index) => (
          <Skeleton key={index} className="h-3.5 w-full last:w-2/3" />
        ))}
      </div>
    );
  }

  return (
    <div className={cn('space-y-2', className)} role="status" aria-label={label}>
      {items.map((index) => (
        <Skeleton key={index} className="h-9 w-full" />
      ))}
    </div>
  );
}
