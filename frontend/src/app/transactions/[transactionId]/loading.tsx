import { Skeleton } from '@/components/ui/skeleton';
import { LoadingState } from '@/components/shared/LoadingState';

/** Header summary + meter card, then the tab strip and a tab body. */
export default function TransactionDetailLoading() {
  return (
    <div aria-busy="true" role="status" aria-label="Loading transaction detail">
      <div className="space-y-2 pb-5">
        <Skeleton className="h-3.5 w-32" />
        <Skeleton className="h-6 w-56" />
        <Skeleton className="h-4 w-[34rem] max-w-full" />
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Skeleton className="h-52 w-full" />
        <Skeleton className="h-52 w-full" />
      </div>

      <Skeleton className="mt-5 h-9 w-96 max-w-full" />
      <LoadingState variant="rows" count={7} className="mt-4" />
    </div>
  );
}
