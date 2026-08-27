'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/shared/ErrorState';

/**
 * Route-level boundary for `/policies`. Unpublished edits live in component state, so a crash
 * here loses them - the message says so rather than pretending a retry restores them.
 */
export default function PoliciesError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('[pdei] /policies failed', error);
  }, [error]);

  return (
    <div className="mx-auto max-w-2xl space-y-4 py-10">
      <ErrorState error={error} onRetry={reset} title="The policy console failed to load" />
      <p className="text-xs text-muted-foreground">
        Any edits that had not been published are gone. Published versions are unaffected: they
        are immutable rows in <span className="mono-id">pdei.policy_versions</span>.
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
          <Link href="/cases">Go to cases</Link>
        </Button>
      </div>
    </div>
  );
}
