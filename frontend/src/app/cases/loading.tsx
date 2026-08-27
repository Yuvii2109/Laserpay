import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Skeleton shaped like the case board: header, KPI row, then four lanes. */
export default function CasesLoading() {
  return (
    <div className="space-y-5" aria-busy="true">
      <div className="space-y-2">
        <Skeleton className="h-3 w-16" />
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-4 w-[32rem] max-w-full" />
      </div>
      <LoadingState variant="cards" count={4} label="Loading case counters" />
      <div className="flex gap-3 overflow-hidden">
        {[0, 1, 2, 3].map((lane) => (
          <div key={lane} className="w-[19rem] shrink-0 space-y-2">
            <Skeleton className="h-14 w-full" />
            <Skeleton className="h-28 w-full" />
            <Skeleton className="h-28 w-full" />
          </div>
        ))}
      </div>
    </div>
  );
}
