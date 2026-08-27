import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Shaped like the Control Tower: heading, five KPI tiles, then the two panel columns. */
export default function ControlTowerLoading() {
  return (
    <div aria-busy="true" role="status" aria-label="Loading the Control Tower">
      <div className="space-y-2 pb-5">
        <Skeleton className="h-6 w-64" />
        <Skeleton className="h-4 w-[28rem] max-w-full" />
      </div>

      <LoadingState variant="cards" count={5} className="xl:grid-cols-5" />

      <div className="mt-4 grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="space-y-4">
          <Skeleton className="h-64 w-full" />
          <Skeleton className="h-72 w-full" />
        </div>
        <div className="space-y-4">
          <Skeleton className="h-[26rem] w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      </div>
    </div>
  );
}
