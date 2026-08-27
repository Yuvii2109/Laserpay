import { LoadingState } from '@/components/shared/LoadingState';
import { Skeleton } from '@/components/ui/skeleton';

/** Skeleton shaped like the settings stack: merchant grid, endpoints, AI, mocks, preferences. */
export default function SettingsLoading() {
  return (
    <div className="space-y-6" aria-busy="true">
      <div className="space-y-2">
        <Skeleton className="h-3 w-14" />
        <Skeleton className="h-6 w-32" />
        <Skeleton className="h-4 w-[32rem] max-w-full" />
      </div>
      <LoadingState variant="cards" count={3} label="Loading merchants" />
      <Skeleton className="h-56 w-full" />
      <Skeleton className="h-48 w-full" />
      <Skeleton className="h-40 w-full" />
    </div>
  );
}
