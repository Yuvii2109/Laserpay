'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/shared/ErrorState';
import { UI_STORAGE_KEY } from '@/lib/store/uiStore';

/**
 * Route-level boundary for `/settings`.
 *
 * A corrupt persisted preferences blob is one plausible cause, so the recovery path names the
 * localStorage key rather than leaving the operator to guess.
 */
export default function SettingsError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('[pdei] /settings failed', error);
  }, [error]);

  return (
    <div className="mx-auto max-w-2xl space-y-4 py-10">
      <ErrorState error={error} onRetry={reset} title="Settings failed to load" />
      <p className="text-xs text-muted-foreground">
        If this persists, clear the persisted preferences for this origin (localStorage key{' '}
        <span className="mono-id">{UI_STORAGE_KEY}</span>) and reload. No platform data is stored
        in the browser.
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
          <Link href="/control-tower">Back to Control Tower</Link>
        </Button>
      </div>
    </div>
  );
}
