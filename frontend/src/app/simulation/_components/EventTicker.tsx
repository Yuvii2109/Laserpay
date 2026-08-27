'use client';

import * as React from 'react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ConnectionIndicator } from '@/components/shared/ConnectionIndicator';
import { LiveEventTicker } from '@/components/shared/LiveEventTicker';
import { useLiveStore } from '@/lib/store/liveStore';
import type { WsFrameType } from '@/lib/types/ws';

export interface EventTickerProps {
  /** Bump this to flash the panel - used when an injection or replay is accepted. */
  pulseKey: number;
  onReconnect?: () => void;
  className?: string;
}

/**
 * The chaos console's view of the live tail.
 *
 * The rows are the shared `LiveEventTicker`; what this panel adds is the pair of numbers that
 * make a chaos run legible. Frames-per-type shows what the platform emitted in response to an
 * injection, and **duplicates dropped** is the demonstration itself: under DUPLICATE_EVENT or a
 * replay, that counter climbing while nothing else moves is idempotency working (contract 17,
 * rule 9).
 *
 * An injection is an HTTP call; its consequences arrive here as frames, so the panel flashes on
 * `pulseKey` to tie the two together in time.
 */
export function EventTicker({ pulseKey, onReconnect, className }: EventTickerProps) {
  const counts = useLiveStore((state) => state.counts);
  const duplicatesDropped = useLiveStore((state) => state.duplicatesDropped);
  const clearTail = useLiveStore((state) => state.clearTail);
  const eventCount = useLiveStore((state) => state.events.length);
  const [flash, setFlash] = React.useState(false);

  React.useEffect(() => {
    if (pulseKey === 0) return;
    setFlash(true);
    const timer = window.setTimeout(() => setFlash(false), 1400);
    return () => window.clearTimeout(timer);
  }, [pulseKey]);

  const totalFrames = Object.values(counts).reduce((sum, value) => sum + value, 0);
  const activeTypes = (Object.keys(counts) as WsFrameType[]).filter((type) => counts[type] > 0);

  return (
    <section
      className={cn(
        'surface-card flex min-h-0 flex-col p-4 transition-shadow',
        flash && 'ring-2 ring-primary',
        className,
      )}
      aria-label="Live event ticker"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-foreground">Live events</h2>
        <ConnectionIndicator {...(onReconnect ? { onReconnect } : {})} />
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-2xs text-muted-foreground">
        <span className="tabular">{totalFrames} frames this session</span>
        <span
          className="tabular"
          title="Frames whose identity was already in the tail. Under duplicate chaos or a replay this is the number that should move - and the only one."
          style={duplicatesDropped > 0 ? { color: 'var(--status-good)' } : undefined}
        >
          {duplicatesDropped} duplicates dropped
        </span>
        <Button
          variant="ghost"
          size="sm"
          className="ml-auto h-6 px-2 text-2xs"
          onClick={clearTail}
          disabled={eventCount === 0}
        >
          Clear
        </Button>
      </div>

      {activeTypes.length > 0 ? (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {activeTypes.map((type) => (
            <Badge key={type} variant="outline" className="gap-1 text-2xs">
              {type} <span className="tabular">{counts[type]}</span>
            </Badge>
          ))}
        </div>
      ) : null}

      <LiveEventTicker className="mt-3" limit={60} maxHeight="20rem" />
    </section>
  );
}
