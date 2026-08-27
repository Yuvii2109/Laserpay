'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ErrorState } from './ErrorState';

export interface RouteErrorBoundaryProps {
  error: Error & { digest?: string };
  reset: () => void;
  /** Route name, used in the heading and in the console line. */
  route: string;
  /** Where "go back" should land. Defaults to the Control Tower. */
  backHref?: string;
  backLabel?: string;
}

/**
 * The body of every route-level `error.tsx`.
 *
 * Next requires one file per route segment, but the behaviour is identical everywhere: name the
 * route that failed, surface the correlation id (from `ApiError`) and Next's `digest`, offer a
 * retry that re-renders the segment, and keep a way out. Rendering inside the shell means the
 * sidebar still works while one route is broken.
 */
export function RouteErrorBoundary({
  error,
  reset,
  route,
  backHref = '/control-tower',
  backLabel = 'Back to Control Tower',
}: RouteErrorBoundaryProps) {
  useEffect(() => {
    console.error(`[pdei] route error in ${route}`, error);
  }, [error, route]);

  return (
    <div className="mx-auto max-w-2xl space-y-4 py-10">
      <ErrorState error={error} onRetry={reset} title={`${route} failed to render`} />

      {error.digest ? (
        <p className="text-xs text-muted-foreground">
          Digest: <span className="mono-id">{error.digest}</span>
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        <Button onClick={reset}>
          <RefreshCw className="size-4" aria-hidden />
          Try again
        </Button>
        <Button variant="outline" asChild>
          <Link href={backHref}>{backLabel}</Link>
        </Button>
      </div>
    </div>
  );
}
