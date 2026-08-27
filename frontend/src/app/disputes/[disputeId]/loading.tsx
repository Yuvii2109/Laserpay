import { Skeleton } from '@/components/ui/skeleton';

/** Dispute facts + countdown, then transaction / case, then the checklist. */
export default function DisputeDetailLoading() {
  return (
    <div aria-busy="true" role="status" aria-label="Loading dispute detail">
      <div className="space-y-2 pb-5">
        <Skeleton className="h-3.5 w-28" />
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-4 w-[30rem] max-w-full" />
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Skeleton className="h-56 w-full" />
        <Skeleton className="h-56 w-full" />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Skeleton className="h-72 w-full" />
        <Skeleton className="h-72 w-full" />
      </div>

      <Skeleton className="mt-4 h-64 w-full" />
    </div>
  );
}
