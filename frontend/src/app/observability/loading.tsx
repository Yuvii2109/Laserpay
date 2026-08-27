import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Skeleton shaped like the observability page: KPI row, funnel, consoles, health grid. */
export default function ObservabilityLoading() {
  return (
    <div className="space-y-6" aria-busy="true">
      <div className="space-y-2">
        <Skeleton className="h-3 w-14" />
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-4 w-[36rem] max-w-full" />
      </div>
      <LoadingState variant="cards" count={4} label="Loading funnel counters" />
      <Skeleton className="h-80 w-full" />
      <div className="grid gap-3 sm:grid-cols-2">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-24 w-full" />
      </div>
      <LoadingState variant="cards" count={4} label="Loading service health" />
    </div>
  );
}
