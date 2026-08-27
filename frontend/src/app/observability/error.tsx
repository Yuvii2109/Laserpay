'use client';

import { useEffect } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/shared/ErrorState';

/**
 * Route-level boundary for `/observability`.
 *
 * The external consoles are plain links and do not depend on this page rendering, so they are
 * repeated here: when the console is broken, Grafana and Temporal are exactly where an operator
 * needs to go next.
 */
export default function ObservabilityError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('[pdei] /observability failed', error);
  }, [error]);

  return (
    <div className="mx-auto max-w-2xl space-y-4 py-10">
      <ErrorState error={error} onRetry={reset} title="The observability view failed to load" />
      {error.digest ? (
        <p className="text-xs text-muted-foreground">
          Digest: <span className="mono-id">{error.digest}</span>
        </p>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Button onClick={reset}>
          <RefreshCw className="size-4" />
          Try again
        </Button>
        <Button variant="outline" asChild>
          <a href="http://localhost:3001" target="_blank" rel="noreferrer">
            Open Grafana
          </a>
        </Button>
        <Button variant="outline" asChild>
          <a href="http://localhost:8233" target="_blank" rel="noreferrer">
            Open Temporal UI
          </a>
        </Button>
      </div>
    </div>
  );
}
