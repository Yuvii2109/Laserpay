'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/shared/ErrorState';

/**
 * Route-level error boundary. Renders inside the shell, so navigation stays available while
 * one route is broken. `digest` is Next's server-error fingerprint; it is shown because it is
 * the only handle an operator has on a server-side stack trace.
 */
export default function RouteError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('[pdei] route error', error);
  }, [error]);

  return (
    <div className="mx-auto max-w-2xl space-y-4 py-10">
      <ErrorState error={error} onRetry={reset} title="This view failed to render" />
      {error.digest ? (
        <p className="text-xs text-muted-foreground">
          Digest: <span className="mono-id">{error.digest}</span>
        </p>
      ) : null}
      <div className="flex gap-2">
        <Button onClick={reset}>
          <RefreshCw className="size-4" />
          Try again
        </Button>
        <Button variant="outline" asChild>
          <Link href="/control-tower">Back to Control Tower</Link>
        </Button>
      </div>
    </div>
  );
}
