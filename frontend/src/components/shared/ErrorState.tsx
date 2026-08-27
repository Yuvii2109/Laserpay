'use client';

import { AlertTriangle, PlugZap, RefreshCw } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { isApiError } from '@/lib/api/client';
import { config } from '@/lib/config';

export interface ErrorStateProps {
  error: unknown;
  onRetry?: () => void;
  className?: string;
  title?: string;
  compact?: boolean;
}

/**
 * Failure surface for any query or mutation.
 *
 * It distinguishes "the gateway is not running" from "the gateway said no", because during
 * local development the first is overwhelmingly the common case and the fix is different.
 * The correlation id is always shown: it is the key into the platform's own logs.
 */
export function ErrorState({ error, onRetry, className, title, compact = false }: ErrorStateProps) {
  const apiError = isApiError(error) ? error : null;
  const offline = apiError?.status === 0;
  const message =
    apiError?.message ??
    (error instanceof Error ? error.message : 'Something went wrong while loading this view.');

  return (
    <div
      className={cn(
        'flex flex-col items-start gap-3 rounded-lg border border-[color:var(--status-critical)]/35 bg-[color:var(--status-critical)]/8',
        compact ? 'p-4' : 'p-6',
        className,
      )}
      role="alert"
    >
      <div className="flex items-center gap-2">
        {offline ? (
          <PlugZap className="size-4" style={{ color: 'var(--status-critical)' }} aria-hidden />
        ) : (
          <AlertTriangle className="size-4" style={{ color: 'var(--status-critical)' }} aria-hidden />
        )}
        <p className="text-sm font-medium text-foreground">
          {title ?? (offline ? 'Cannot reach api-gateway-service' : 'Request failed')}
        </p>
      </div>

      <p className="text-sm text-muted-foreground">{message}</p>

      {offline ? (
        <p className="text-xs text-muted-foreground">
          Expected at <span className="mono-id">{config.apiBaseUrl}</span>. Start the backend, or run
          the console with <span className="mono-id">NEXT_PUBLIC_USE_MOCKS=true</span> to explore it
          against deterministic fixtures.
        </p>
      ) : null}

      {apiError ? (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <dt>Code</dt>
          <dd className="mono-id">{apiError.code}</dd>
          <dt>Status</dt>
          <dd className="mono-id">{apiError.status}</dd>
          {apiError.correlationId ? (
            <>
              <dt>Correlation</dt>
              <dd className="mono-id">{apiError.correlationId}</dd>
            </>
          ) : null}
        </dl>
      ) : null}

      {onRetry ? (
        <Button size="sm" variant="outline" onClick={onRetry}>
          <RefreshCw className="size-3.5" />
          Retry
        </Button>
      ) : null}
    </div>
  );
}
