import { Skeleton } from '@/components/ui/skeleton';

/** Provenance + integrity side by side, then the two history panels. */
export default function EvidenceDetailLoading() {
  return (
    <div aria-busy="true" role="status" aria-label="Loading evidence detail">
      <div className="space-y-2 pb-5">
        <Skeleton className="h-3.5 w-36" />
        <Skeleton className="h-6 w-64" />
        <Skeleton className="h-4 w-[30rem] max-w-full" />
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <Skeleton className="h-80 w-full" />
        <Skeleton className="h-80 w-full" />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Skeleton className="h-64 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    </div>
  );
}
