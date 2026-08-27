import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Heading, the two filter selects, then the dispute table. */
export default function DisputesLoading() {
  return (
    <div aria-busy="true" role="status" aria-label="Loading disputes">
      <div className="space-y-2 pb-5">
        <Skeleton className="h-6 w-36" />
        <Skeleton className="h-4 w-[34rem] max-w-full" />
      </div>
      <Skeleton className="mb-4 h-20 w-full" />
      <LoadingState variant="rows" count={10} />
    </div>
  );
}
