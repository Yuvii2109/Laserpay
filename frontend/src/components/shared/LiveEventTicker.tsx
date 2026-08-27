'use client';

import * as React from 'react';
import Link from 'next/link';
import { Radio, RotateCcw } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { formatMoney } from '@/lib/format/money';
import { formatTime } from '@/lib/format/date';
import { humanizeEnum } from '@/lib/format/id';
import { BAND_LABEL, bandColorVar, toneColorVar, type Tone } from '@/lib/format/score';
import { useLiveStore, selectLiveEvents } from '@/lib/store/liveStore';
import { useUiStore } from '@/lib/store/uiStore';
import type { LiveEvent, WsFrame, WsFrameType } from '@/lib/types/ws';

export interface LiveEventTickerProps {
  /** Rows kept on screen. The store's tail is longer (LIVE_TAIL_LIMIT). */
  limit?: number;
  className?: string;
  /** Height of the scrolling area. */
  maxHeight?: string;
}

const FRAME_TONE: Readonly<Record<WsFrameType, Tone>> = {
  READINESS_UPDATED: 'info',
  EVIDENCE_ADDED: 'good',
  DISPUTE_CREATED: 'serious',
  CASE_UPDATED: 'info',
  GAP_DETECTED: 'warning',
  CHAOS_INJECTED: 'critical',
  HEARTBEAT: 'neutral',
};

const FRAME_LABEL: Readonly<Record<WsFrameType, string>> = {
  READINESS_UPDATED: 'Readiness',
  EVIDENCE_ADDED: 'Evidence',
  DISPUTE_CREATED: 'Dispute',
  CASE_UPDATED: 'Case',
  GAP_DETECTED: 'Gap',
  CHAOS_INJECTED: 'Chaos',
  HEARTBEAT: 'Heartbeat',
};

interface FrameSummary {
  /** The primary business id, used as the row's link target and as its accessible anchor. */
  subject: string;
  detail: React.ReactNode;
  /** Plain-text mirror of `detail` for the screen-reader announcement. */
  spoken: string;
  href: string | null;
}

/** One line per frame type. Frames are notifications, so this reads them, never re-derives them. */
function describeFrame(frame: WsFrame): FrameSummary {
  switch (frame.type) {
    case 'READINESS_UPDATED': {
      const { transactionId, score, band, previousScore } = frame.data;
      const movement = previousScore === null ? `scored ${score}` : `${previousScore} → ${score}`;
      return {
        subject: transactionId,
        detail: (
          <>
            {movement}{' '}
            <span style={{ color: bandColorVar(band) }}>{BAND_LABEL[band]}</span>
          </>
        ),
        spoken: `readiness ${movement}, ${BAND_LABEL[band]}`,
        href: `/transactions/${transactionId}`,
      };
    }
    case 'EVIDENCE_ADDED': {
      const { evidenceId, type, version, status } = frame.data;
      const text = `${humanizeEnum(type)} v${version} · ${humanizeEnum(status)}`;
      return { subject: evidenceId, detail: text, spoken: text, href: `/evidence/${evidenceId}` };
    }
    case 'DISPUTE_CREATED': {
      const { disputeId, reasonCode, amount } = frame.data;
      const text = `${humanizeEnum(reasonCode)} · ${formatMoney(amount)}`;
      return { subject: disputeId, detail: text, spoken: text, href: `/disputes/${disputeId}` };
    }
    case 'CASE_UPDATED': {
      const { caseId, status, previousStatus, awaitingApproval } = frame.data;
      const movement = previousStatus
        ? `${humanizeEnum(previousStatus)} → ${humanizeEnum(status)}`
        : humanizeEnum(status);
      const text = awaitingApproval ? `${movement} · awaiting approval` : movement;
      return { subject: caseId, detail: text, spoken: text, href: `/cases/${caseId}` };
    }
    case 'GAP_DETECTED': {
      // Only transactionId is guaranteed on the wire; the rest is rendered when it arrives.
      const { transactionId, type, severity, evidenceType, band } = frame.data;
      const parts = [type ? humanizeEnum(type) : 'Readiness gap'];
      if (evidenceType) parts.push(humanizeEnum(evidenceType));
      if (severity) parts.push(humanizeEnum(severity));
      else if (band) parts.push(humanizeEnum(band));
      const text = parts.join(' · ');
      return {
        subject: transactionId,
        detail: text,
        spoken: text,
        href: `/transactions/${transactionId}`,
      };
    }
    case 'CHAOS_INJECTED': {
      const { injectionId, type } = frame.data;
      const text = `${humanizeEnum(type)} injected`;
      return { subject: injectionId, detail: text, spoken: text, href: '/simulation' };
    }
    case 'HEARTBEAT':
    default:
      return { subject: '—', detail: 'heartbeat', spoken: 'heartbeat', href: null };
  }
}

/**
 * The live event ticker.
 *
 * It renders the WebSocket tail held in `liveStore` - a notification channel, never a source
 * of truth. Every frame that changes data has already been turned into a query invalidation by
 * `useInvalidateOnWsEvent`, so the panels beside this ticker refresh from REST on their own;
 * the ticker only says *what* moved and *when it arrived here*.
 *
 * `aria-live="polite"` with `aria-relevant="additions"` announces new rows without re-reading
 * the whole list, and the region is a labelled `<section>` so it can be reached directly.
 */
export function LiveEventTicker({ limit = 40, className, maxHeight = '22rem' }: LiveEventTickerProps) {
  const events = useLiveStore(selectLiveEvents);
  const duplicatesDropped = useLiveStore((state) => state.duplicatesDropped);
  const clearTail = useLiveStore((state) => state.clearTail);
  const timeZoneMode = useUiStore((state) => state.timeZoneMode);

  const rows = React.useMemo(() => events.slice(0, limit), [events, limit]);

  return (
    <section
      aria-label="Live event ticker"
      className={cn('flex min-h-0 flex-col rounded-lg border border-border bg-card', className)}
    >
      <header className="flex items-center justify-between gap-2 border-b border-border px-4 py-2.5">
        <div className="flex items-center gap-2">
          <Radio className="size-4 text-muted-foreground" aria-hidden />
          <h2 className="text-sm font-semibold tracking-tight">Live events</h2>
          <span className="text-xs text-muted-foreground">
            {events.length} in tail
            {duplicatesDropped > 0 ? ` · ${duplicatesDropped} duplicate${duplicatesDropped === 1 ? '' : 's'} dropped` : ''}
          </span>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          onClick={clearTail}
          disabled={events.length === 0 && duplicatesDropped === 0}
          aria-label="Clear the live tail"
        >
          <RotateCcw className="size-3.5" aria-hidden />
        </Button>
      </header>

      <div
        className="min-h-0 flex-1 overflow-y-auto scrollbar-thin"
        style={{ maxHeight }}
        aria-live="polite"
        aria-atomic="false"
        aria-relevant="additions"
      >
        {rows.length === 0 ? (
          <p className="px-4 py-8 text-center text-sm text-muted-foreground">
            No frames yet. The control-tower socket pushes readiness, evidence, dispute, case,
            gap and chaos notifications as the platform produces them.
          </p>
        ) : (
          <ul className="divide-y divide-border">
            {rows.map((event) => (
              <TickerRow key={event.seq} event={event} timeZoneMode={timeZoneMode} />
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

function TickerRow({
  event,
  timeZoneMode,
}: {
  event: LiveEvent;
  timeZoneMode: 'utc' | 'local';
}) {
  const { frame, receivedAt } = event;
  const summary = describeFrame(frame);
  const color = toneColorVar(FRAME_TONE[frame.type]);
  const label = FRAME_LABEL[frame.type];
  const late = new Date(receivedAt).getTime() - new Date(frame.at).getTime();

  return (
    <li className="flex items-start gap-3 px-4 py-2 text-sm">
      <time
        dateTime={receivedAt}
        className="tabular w-[4.5rem] shrink-0 pt-0.5 text-xs text-muted-foreground"
        title={`Frame stamped ${frame.at}, received ${receivedAt}`}
      >
        {formatTime(receivedAt, timeZoneMode)}
      </time>

      <span
        className="mt-0.5 w-16 shrink-0 text-xs font-medium"
        style={{ color }}
        aria-hidden
      >
        {label}
      </span>

      <span className="min-w-0 flex-1">
        <span className="sr-only">{`${label}: `}</span>
        {summary.href ? (
          <Link
            href={summary.href}
            className="mono-id text-foreground underline-offset-4 hover:underline"
          >
            {summary.subject}
          </Link>
        ) : (
          <span className="mono-id text-foreground">{summary.subject}</span>
        )}
        <span className="ml-2 text-muted-foreground">{summary.detail}</span>
        {/* Late arrival is worth seeing: contract 17 rule 10 says assume it. */}
        {late > 5_000 ? (
          <span className="ml-2 text-xs text-muted-foreground" title={`Stamped at ${frame.at}`}>
            (arrived {Math.round(late / 1000)}s late)
          </span>
        ) : null}
      </span>
    </li>
  );
}
