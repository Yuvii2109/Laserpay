import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Heading, filter bar, then a table's worth of rows. */
export default function TransactionsLoading() {
  return (
    <div aria-busy="true" role="status" aria-label="Loading transactions">
      <div className="space-y-2 pb-5">
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-4 w-[32rem] max-w-full" />
      </div>
      <Skeleton className="mb-4 h-20 w-full" />
      <LoadingState variant="rows" count={10} />
    </div>
  );
}
