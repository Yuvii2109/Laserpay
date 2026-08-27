import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Skeleton shaped like the policy console: header, matrix block, thresholds, history. */
export default function PoliciesLoading() {
  return (
    <div className="space-y-5" aria-busy="true">
      <div className="space-y-2">
        <Skeleton className="h-3 w-16" />
        <Skeleton className="h-6 w-40" />
        <Skeleton className="h-4 w-[36rem] max-w-full" />
      </div>
      <Skeleton className="h-64 w-full" />
      <LoadingState variant="panel" label="Loading automation thresholds" />
      <LoadingState variant="rows" count={5} label="Loading version history" />
    </div>
  );
}
