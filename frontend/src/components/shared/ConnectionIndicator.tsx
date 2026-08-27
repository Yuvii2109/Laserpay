'use client';

import { RotateCw } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useLiveStore } from '@/lib/store/liveStore';
import { formatRelative } from '@/lib/format/date';
import { CONNECTION_LABEL, type ConnectionStatus } from '@/lib/types/ws';
import { config } from '@/lib/config';

const STATUS_COLOR: Readonly<Record<ConnectionStatus, string>> = {
  idle: 'var(--status-neutral)',
  connecting: 'var(--status-warning)',
  open: 'var(--status-good)',
  reconnecting: 'var(--status-warning)',
  closed: 'var(--status-critical)',
  error: 'var(--status-critical)',
  mock: 'var(--chart-7)',
};

const PULSING: readonly ConnectionStatus[] = ['connecting', 'reconnecting', 'open'];

export interface ConnectionIndicatorProps {
  className?: string;
  /** Provided by the shell so the operator can force a reconnect. */
  onReconnect?: () => void;
  compact?: boolean;
}

/**
 * Global control-tower socket state.
 *
 * The dot is never the only signal: the label spells the state out, and the tooltip carries the
 * last frame time, the reconnect attempt count and the endpoint being dialled.
 */
export function ConnectionIndicator({ className, onReconnect, compact = false }: ConnectionIndicatorProps) {
  const status = useLiveStore((state) => state.status);
  const lastFrameAt = useLiveStore((state) => state.lastFrameAt);
  const reconnectAttempts = useLiveStore((state) => state.reconnectAttempts);
  const lastError = useLiveStore((state) => state.lastError);
  const duplicatesDropped = useLiveStore((state) => state.duplicatesDropped);

  const color = STATUS_COLOR[status];
  const label = CONNECTION_LABEL[status];
  const degraded = status === 'closed' || status === 'error' || status === 'reconnecting';

  return (
    <div className={cn('flex items-center gap-2', className)}>
      <Tooltip>
        <TooltipTrigger asChild>
          <span
            className="inline-flex items-center gap-2 rounded-md border border-border px-2 py-1 text-xs"
            role="status"
            aria-live="polite"
            data-connection={status}
          >
            <span
              className={cn('size-2 rounded-full', PULSING.includes(status) && 'animate-pulse-dot')}
              style={{ backgroundColor: color }}
              aria-hidden
            />
            {compact ? <span className="sr-only">{label}</span> : <span>{label}</span>}
          </span>
        </TooltipTrigger>
        <TooltipContent side="bottom" className="space-y-1">
          <p className="font-medium">{label}</p>
          <p className="text-muted-foreground">
            {status === 'mock' ? 'Deterministic fixture feed (NEXT_PUBLIC_USE_MOCKS=true)' : config.wsUrl}
          </p>
          <p className="text-muted-foreground">
            Last frame: {lastFrameAt ? formatRelative(lastFrameAt) : 'none yet'}
          </p>
          {reconnectAttempts > 0 ? (
            <p className="text-muted-foreground">Reconnect attempts: {reconnectAttempts}</p>
          ) : null}
          {duplicatesDropped > 0 ? (
            <p className="text-muted-foreground">Duplicate frames dropped: {duplicatesDropped}</p>
          ) : null}
          {lastError ? <p className="text-muted-foreground">{lastError}</p> : null}
        </TooltipContent>
      </Tooltip>

      {degraded && onReconnect ? (
        <Button size="icon-sm" variant="ghost" onClick={onReconnect} aria-label="Reconnect now">
          <RotateCw className="size-3.5" />
        </Button>
      ) : null}
    </div>
  );
}
