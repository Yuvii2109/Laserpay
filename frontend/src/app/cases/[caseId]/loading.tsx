import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Skeleton shaped like the X-Ray: header, id strip, tab bar, then the overview grid. */
export default function CaseDetailLoading() {
  return (
    <div className="space-y-5" aria-busy="true">
      <div className="space-y-2">
        <Skeleton className="h-3 w-24" />
        <Skeleton className="h-6 w-56" />
        <Skeleton className="h-4 w-[34rem] max-w-full" />
      </div>
      <div className="flex gap-3">
        <Skeleton className="h-3 w-32" />
        <Skeleton className="h-3 w-32" />
        <Skeleton className="h-3 w-24" />
      </div>
      <Skeleton className="h-9 w-full max-w-3xl" />
      <LoadingState variant="cards" count={4} label="Loading case facts" />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <LoadingState variant="panel" label="Loading dispute facts" />
        <LoadingState variant="panel" label="Loading readiness" />
      </div>
    </div>
  );
}
