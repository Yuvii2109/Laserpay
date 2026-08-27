/**
 * Live state fed by the control-tower WebSocket (contract 8.1 streaming).
 *
 * This store holds only what arrived over the wire: connection status and a bounded tail of
 * frames. It is never the source of truth for domain state - frames are notifications, and
 * `useInvalidateOnWsEvent` turns them into TanStack Query invalidations so the authoritative
 * value is re-fetched over REST.
 *
 * Duplicate and out-of-order frames are expected (contract 17 rules 9 and 10): the tail
 * de-duplicates on a per-frame identity key and keeps arrival order, not event order.
 */
'use client';

import { useMemo } from 'react';
import { create } from 'zustand';
import type { ConnectionStatus, LiveEvent, WsFrame, WsFrameType } from '@/lib/types/ws';

/** How many frames the tail keeps. Older frames fall off the end. */
export const LIVE_TAIL_LIMIT = 200;

export interface LiveState {
  status: ConnectionStatus;
  /** The merchant the socket is subscribed to, mirrored for display. */
  merchantId: string | null;
  /** Failed connection attempts since the last successful open. Drives the backoff label. */
  reconnectAttempts: number;
  connectedAt: string | null;
  lastFrameAt: string | null;
  lastHeartbeatAt: string | null;
  lastError: string | null;
  /** Newest first. */
  events: LiveEvent[];
  /** Frames received per type this session - the live counters on the control tower. */
  counts: Record<WsFrameType, number>;
  /** Frames dropped as duplicates; surfaced so chaos runs can show idempotency working. */
  duplicatesDropped: number;

  setStatus: (status: ConnectionStatus, detail?: { error?: string | null }) => void;
  setMerchantId: (merchantId: string | null) => void;
  markConnected: () => void;
  noteReconnectAttempt: () => void;
  pushFrame: (frame: WsFrame, receivedAt?: string) => void;
  clearTail: () => void;
  reset: () => void;
}

const ZERO_COUNTS: Record<WsFrameType, number> = {
  READINESS_UPDATED: 0,
  EVIDENCE_ADDED: 0,
  DISPUTE_CREATED: 0,
  CASE_UPDATED: 0,
  GAP_DETECTED: 0,
  CHAOS_INJECTED: 0,
  HEARTBEAT: 0,
};

/**
 * Identity of a frame for de-duplication. The envelope carries no event id, so identity is
 * (type, at, merchantId, primary business id) - stable for a redelivered frame and different
 * for a genuinely new one.
 */
function frameIdentity(frame: WsFrame): string {
  const data = frame.data as Record<string, unknown>;
  const primary =
    (data?.['gapId'] as string) ??
    (data?.['evidenceId'] as string) ??
    (data?.['caseId'] as string) ??
    (data?.['disputeId'] as string) ??
    (data?.['injectionId'] as string) ??
    (data?.['transactionId'] as string) ??
    '';
  return `${frame.type}|${frame.at}|${frame.merchantId}|${primary}`;
}

let sequence = 0;

export const useLiveStore = create<LiveState>()((set, get) => ({
  status: 'idle',
  merchantId: null,
  reconnectAttempts: 0,
  connectedAt: null,
  lastFrameAt: null,
  lastHeartbeatAt: null,
  lastError: null,
  events: [],
  counts: { ...ZERO_COUNTS },
  duplicatesDropped: 0,

  setStatus: (status, detail) =>
    set({ status, lastError: detail?.error ?? (status === 'error' ? get().lastError : null) }),

  setMerchantId: (merchantId) =>
    set((state) =>
      state.merchantId === merchantId
        ? state
        : { merchantId, events: [], counts: { ...ZERO_COUNTS }, duplicatesDropped: 0 },
    ),

  markConnected: () =>
    set({
      status: 'open',
      connectedAt: new Date().toISOString(),
      reconnectAttempts: 0,
      lastError: null,
    }),

  noteReconnectAttempt: () =>
    set((state) => ({ reconnectAttempts: state.reconnectAttempts + 1, status: 'reconnecting' })),

  pushFrame: (frame, receivedAt = new Date().toISOString()) =>
    set((state) => {
      const counts = { ...state.counts, [frame.type]: (state.counts[frame.type] ?? 0) + 1 };

      if (frame.type === 'HEARTBEAT') {
        // Heartbeats keep the watchdog alive but never enter the tail.
        return { counts, lastHeartbeatAt: receivedAt, lastFrameAt: receivedAt };
      }

      const identity = frameIdentity(frame);
      const duplicate = state.events.some((event) => frameIdentity(event.frame) === identity);
      if (duplicate) {
        return { counts, lastFrameAt: receivedAt, duplicatesDropped: state.duplicatesDropped + 1 };
      }

      sequence += 1;
      const event: LiveEvent = { seq: sequence, receivedAt, frame };
      return {
        counts,
        lastFrameAt: receivedAt,
        events: [event, ...state.events].slice(0, LIVE_TAIL_LIMIT),
      };
    }),

  clearTail: () => set({ events: [], counts: { ...ZERO_COUNTS }, duplicatesDropped: 0 }),

  reset: () =>
    set({
      status: 'idle',
      reconnectAttempts: 0,
      connectedAt: null,
      lastFrameAt: null,
      lastHeartbeatAt: null,
      lastError: null,
      events: [],
      counts: { ...ZERO_COUNTS },
      duplicatesDropped: 0,
    }),
}));

/* ---- selectors ---- */

export const selectConnectionStatus = (state: LiveState) => state.status;
export const selectLiveEvents = (state: LiveState) => state.events;
export const selectLiveCounts = (state: LiveState) => state.counts;

/**
 * Live tail filtered to one frame type, newest first. The filter runs in a memo rather than
 * inside the selector: a selector that builds a new array on every call re-renders forever
 * under zustand v5's `useSyncExternalStore` snapshot check.
 */
export function useLiveEventsOfType<T extends WsFrameType>(type: T): LiveEvent[] {
  const events = useLiveStore(selectLiveEvents);
  return useMemo(() => events.filter((event) => event.frame.type === type), [events, type]);
}

export function useConnectionStatus(): ConnectionStatus {
  return useLiveStore(selectConnectionStatus);
}
