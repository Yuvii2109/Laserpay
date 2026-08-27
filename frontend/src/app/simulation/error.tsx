'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/shared/ErrorState';
import { config } from '@/lib/config';

/**
 * Route-level boundary for `/simulation`.
 *
 * The likeliest cause by far is that simulator-service is not running: it is the one service
 * this console reaches on its own base URL rather than through the gateway.
 */
export default function SimulationError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('[pdei] /simulation failed', error);
  }, [error]);

  return (
    <div className="mx-auto max-w-2xl space-y-4 py-10">
      <ErrorState error={error} onRetry={reset} title="The simulation console failed to load" />
      <p className="text-xs text-muted-foreground">
        simulator-service is expected at <span className="mono-id">{config.simBaseUrl}</span> (host
        port 8088). The rest of the console does not depend on it.
      </p>
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
          <Link href="/observability">Go to observability</Link>
        </Button>
      </div>
    </div>
  );
}
