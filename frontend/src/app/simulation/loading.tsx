import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Skeleton shaped like the console: launcher + ticker, run history, scenarios, chaos grid. */
export default function SimulationLoading() {
  return (
    <div className="space-y-6" aria-busy="true">
      <div className="space-y-2">
        <Skeleton className="h-3 w-14" />
        <Skeleton className="h-6 w-56" />
        <Skeleton className="h-4 w-[38rem] max-w-full" />
      </div>
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_24rem]">
        <div className="space-y-4">
          <Skeleton className="h-72 w-full" />
          <Skeleton className="h-52 w-full" />
        </div>
        <Skeleton className="h-96 w-full" />
      </div>
      <LoadingState variant="rows" count={5} label="Loading run history" />
      <LoadingState variant="cards" count={4} label="Loading scenarios" />
    </div>
  );
}
