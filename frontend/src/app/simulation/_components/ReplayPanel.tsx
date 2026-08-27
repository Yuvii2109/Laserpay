'use client';

import * as React from 'react';
import { useMutation } from '@tanstack/react-query';
import { Rewind } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { toast } from '@/components/ui/sonner';
import { ConfirmDialog } from '@/components/shared/ConfirmDialog';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { simulationApi } from '@/lib/api/endpoints';
import { daysAgoIso } from '@/lib/format/date';
import { useSelectedMerchantId } from '@/lib/store/uiStore';
import type { ReplayRequest, ReplayResult } from '@/lib/types/simulation';
import { KAFKA_TOPICS } from './chaosCatalog';

type Mode = 'offset' | 'timestamp';

export interface ReplayPanelProps {
  onReplayed: () => void;
}

/**
 * Topic replay (`POST /sim/v1/replay`).
 *
 * Replay is the strongest claim this platform makes: consumers are idempotent, so re-emitting a
 * topic from an earlier position must converge on the same state rather than double-count it.
 * The panel therefore says exactly what it expects to happen before the operator commits.
 */
export function ReplayPanel({ onReplayed }: ReplayPanelProps) {
  const merchantId = useSelectedMerchantId();
  const [topic, setTopic] = React.useState<string>(KAFKA_TOPICS[1] ?? 'pdei.canonical.events.v1');
  const [mode, setMode] = React.useState<Mode>('offset');
  const [fromOffset, setFromOffset] = React.useState<number>(0);
  const [fromTimestamp, setFromTimestamp] = React.useState<string>(() => daysAgoIso(1));
  const [scopeToMerchant, setScopeToMerchant] = React.useState(true);
  const [confirmOpen, setConfirmOpen] = React.useState(false);
  const [lastResult, setLastResult] = React.useState<ReplayResult | null>(null);

  const request: ReplayRequest = {
    topic,
    ...(mode === 'offset' ? { fromOffset } : { fromTimestamp }),
    ...(scopeToMerchant && merchantId ? { merchantId } : {}),
  };

  const replayMutation = useMutation({
    mutationFn: () => simulationApi.replay(request),
    onSuccess: (result) => {
      setLastResult(result);
      onReplayed();
      toast.success(`Replayed ${result.eventsReplayed} events`, {
        description: `${result.topic} from ${result.requestedFrom}`,
      });
    },
    onError: (error: Error) => toast.error('Replay failed', { description: error.message }),
  });

  return (
    <section className="surface-card p-4" aria-label="Replay">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <Rewind className="size-4 text-muted-foreground" aria-hidden />
          Replay a topic
        </h2>
        <span className="text-2xs text-muted-foreground">
          POST <span className="mono-id">/sim/v1/replay</span>
        </span>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        Re-emits events from an earlier offset or timestamp. Because every consumer dedupes on{' '}
        <span className="mono-id">eventId</span>, a replay should move the duplicate counters and
        nothing else.
      </p>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <div className="space-y-1.5">
          <Label htmlFor="replay-topic">Topic</Label>
          <select
            id="replay-topic"
            value={topic}
            onChange={(event) => setTopic(event.target.value)}
            className="h-9 w-full rounded-md border border-input bg-card px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            {KAFKA_TOPICS.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="replay-mode">Start from</Label>
          <select
            id="replay-mode"
            value={mode}
            onChange={(event) => setMode(event.target.value as Mode)}
            className="h-9 w-full rounded-md border border-input bg-card px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="offset">Offset</option>
            <option value="timestamp">Timestamp</option>
          </select>
        </div>

        {mode === 'offset' ? (
          <div className="space-y-1.5">
            <Label htmlFor="replay-offset">From offset</Label>
            <Input
              id="replay-offset"
              type="number"
              min={0}
              step={1}
              value={fromOffset}
              onChange={(event) =>
                setFromOffset(Math.max(0, Math.trunc(Number(event.target.value) || 0)))
              }
              className="tabular"
            />
          </div>
        ) : (
          <div className="space-y-1.5">
            <Label htmlFor="replay-timestamp">From timestamp (ISO-8601 UTC)</Label>
            <Input
              id="replay-timestamp"
              value={fromTimestamp}
              onChange={(event) => setFromTimestamp(event.target.value)}
              className="mono-id"
              spellCheck={false}
            />
          </div>
        )}

        <div className="space-y-1.5">
          <Label htmlFor="replay-scope">Scope</Label>
          <label
            id="replay-scope"
            className="flex h-9 cursor-pointer items-center gap-2 rounded-md border border-input bg-card px-3 text-sm"
          >
            <input
              type="checkbox"
              checked={scopeToMerchant}
              onChange={(event) => setScopeToMerchant(event.target.checked)}
              disabled={!merchantId}
              className="size-4 accent-[color:hsl(var(--primary))]"
            />
            {merchantId ? `Only ${merchantId}` : 'No merchant selected'}
          </label>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-border pt-3">
        <Button size="sm" variant="outline" onClick={() => setConfirmOpen(true)} disabled={replayMutation.isPending}>
          <Rewind className="size-3.5" />
          Replay
        </Button>
        {lastResult ? (
          <span className="flex flex-wrap items-center gap-2 text-2xs text-muted-foreground">
            <Badge variant="subtle" className="mono-id text-2xs">
              {lastResult.replayId}
            </Badge>
            <span className="tabular">{lastResult.eventsReplayed} events</span>
            <span>from {lastResult.requestedFrom}</span>
            <TimestampDisplay value={lastResult.startedAt} className="text-2xs" />
          </span>
        ) : null}
      </div>

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="Replay this topic"
        description={
          <>
            Re-emits <span className="mono-id">{topic}</span> from{' '}
            <span className="mono-id">
              {mode === 'offset' ? `offset ${fromOffset}` : fromTimestamp}
            </span>
            . Expect the duplicate counters to climb while readiness scores, evidence and cases
            stay exactly where they are.
          </>
        }
        confirmLabel="Replay"
        onConfirm={async () => {
          await replayMutation.mutateAsync();
          setConfirmOpen(false);
        }}
      />
    </section>
  );
}
