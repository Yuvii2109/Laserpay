import { cn } from '@/lib/utils';

/** Loading placeholder. Keep the footprint of the real content so nothing jumps. */
export function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      data-slot="skeleton"
      className={cn('animate-pulse rounded-md bg-muted', className)}
      {...props}
    />
  );
}
