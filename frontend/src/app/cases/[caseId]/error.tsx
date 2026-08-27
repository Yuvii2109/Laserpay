'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/shared/ErrorState';

/** Route-level boundary for one case. Failing here must not take the queue down with it. */
export default function CaseDetailError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('[pdei] case x-ray failed', error);
  }, [error]);

  return (
    <div className="mx-auto max-w-2xl space-y-4 py-10">
      <ErrorState error={error} onRetry={reset} title="This case could not be rendered" />
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
          <Link href="/cases">
            <ArrowLeft className="size-4" />
            Back to the case queue
          </Link>
        </Button>
      </div>
    </div>
  );
}
