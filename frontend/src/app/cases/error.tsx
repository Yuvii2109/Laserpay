'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/shared/ErrorState';

/** Route-level boundary for `/cases`. The shell stays usable while this view is broken. */
export default function CasesError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('[pdei] /cases failed', error);
  }, [error]);

  return (
    <div className="mx-auto max-w-2xl space-y-4 py-10">
      <ErrorState error={error} onRetry={reset} title="The case queue failed to load" />
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
          <Link href="/disputes">Go to disputes</Link>
        </Button>
      </div>
    </div>
  );
}
