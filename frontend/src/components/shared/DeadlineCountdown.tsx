'use client';

import * as React from 'react';
import { AlarmClock, CalendarClock, OctagonX } from 'lucide-react';
import { cn } from '@/lib/utils';
import { deadlineState, formatInstant, type DeadlineState } from '@/lib/format/date';
import { toneColorVar, type Tone } from '@/lib/format/score';
import { useUiStore } from '@/lib/store/uiStore';
import type { Iso8601 } from '@/lib/types/common';

export interface DeadlineCountdownProps {
  deadlineAt: Iso8601 | null | undefined;
  /** `inline` is a table cell; `block` is the detail-page panel with the absolute date. */
  variant?: 'inline' | 'block';
  className?: string;
  /** How often the remaining time is recomputed. 30 s is enough for a day-scale deadline. */
  tickMs?: number;
}

function toneFor(state: DeadlineState): Tone {
  if (state.passed) return 'critical';
  if (state.urgent) return 'warning';
  if (state.hoursRemaining < 24 * 3) return 'serious';
  return 'neutral';
}

/**
 * Time left before a representment deadline.
 *
 * The 48-hour window is not decorative: contract 9.4 scores `deadlineUrgency` at 1.0 inside
 * it, which is what pushes a case towards the model and towards a human. The countdown ticks
 * client-side only - the instant itself comes from the gateway and is never recomputed here.
 *
 * The first render is deliberately the absolute instant: "now" differs between the server and
 * the browser, and a hydration mismatch on a countdown is a guaranteed console error.
 */
export function DeadlineCountdown({
  deadlineAt,
  variant = 'inline',
  className,
  tickMs = 30_000,
}: DeadlineCountdownProps) {
  const timeZoneMode = useUiStore((state) => state.timeZoneMode);
  const [state, setState] = React.useState<DeadlineState | null>(null);

  React.useEffect(() => {
    if (!deadlineAt) {
      setState(null);
      return () => undefined;
    }
    const update = () => setState(deadlineState(deadlineAt));
    update();
    const timer = setInterval(update, tickMs);
    return () => clearInterval(timer);
  }, [deadlineAt, tickMs]);

  if (!deadlineAt) {
    return (
      <span className={cn('text-muted-foreground', className)} title="No response deadline set">
        No deadline
      </span>
    );
  }

  const absolute = formatInstant(deadlineAt, timeZoneMode);

  if (!state) {
    // Pre-hydration and pre-mount: the absolute instant, which never disagrees with the server.
    return (
      <time dateTime={deadlineAt} className={cn('whitespace-nowrap', className)}>
        {absolute}
      </time>
    );
  }

  const tone = toneFor(state);
  const color = toneColorVar(tone);
  const Icon = state.passed ? OctagonX : state.urgent ? AlarmClock : CalendarClock;

  if (variant === 'block') {
    return (
      <div className={cn('space-y-1', className)}>
        <div className="flex items-center gap-2" style={{ color }}>
          <Icon className="size-4 shrink-0" aria-hidden />
          <span className="text-lg font-semibold leading-none">{state.label}</span>
        </div>
        <time dateTime={deadlineAt} className="block text-xs text-muted-foreground">
          {absolute}
        </time>
        {state.urgent ? (
          <p className="text-xs text-muted-foreground">
            Inside the 48-hour urgency window: admission control scores this case at maximum
            deadline urgency.
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <time
      dateTime={deadlineAt}
      title={absolute}
      className={cn('inline-flex items-center gap-1.5 whitespace-nowrap text-sm', className)}
      style={{ color: tone === 'neutral' ? undefined : color }}
    >
      <Icon className="size-3.5 shrink-0" aria-hidden />
      {state.label}
    </time>
  );
}
