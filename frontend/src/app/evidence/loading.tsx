import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Heading, search + facet rails, then the result table. */
export default function EvidenceLoading() {
  return (
    <div aria-busy="true" role="status" aria-label="Loading the evidence explorer">
      <div className="space-y-2 pb-5">
        <Skeleton className="h-6 w-40" />
        <Skeleton className="h-4 w-[34rem] max-w-full" />
      </div>
      <Skeleton className="mb-4 h-40 w-full" />
      <LoadingState variant="rows" count={10} />
    </div>
  );
}
