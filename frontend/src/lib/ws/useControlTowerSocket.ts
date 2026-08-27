/**
 * Control-tower WebSocket hook (contract 8.1: `WS /ws/control-tower?merchantId=...`).
 *
 * Native WebSocket only - no socket library. Behaviour:
 *   - one socket per merchant; changing merchant closes and reopens cleanly
 *   - reconnect with exponential backoff + jitter, capped, and reset on a successful open
 *   - a heartbeat watchdog: if no frame (heartbeat included) arrives inside the idle window
 *     the socket is considered dead and is recycled, because a half-open TCP connection
 *     reports `readyState === OPEN` forever
 *   - frames are parsed and validated before they reach the store (see parseFrame.ts)
 *   - in mock mode the hook drives a deterministic in-process feed instead of a socket, so
 *     the console is live with the whole backend down
 *
 * The hook owns transport only. Turning frames into refreshed data is `useInvalidateOnWsEvent`.
 */
'use client';

import { useCallback, useEffect, useRef } from 'react';
import { config } from '@/lib/config';
import { useLiveStore } from '@/lib/store/liveStore';
import { parseWsFrame } from './parseFrame';
import type { ConnectionStatus } from '@/lib/types/ws';

export interface ControlTowerSocketOptions {
  /** Merchant to subscribe to. The socket stays closed while this is null. */
  merchantId: string | null;
  /** Set false to keep the socket closed (e.g. on a page that must not stream). */
  enabled?: boolean;
  /** First backoff step in ms. Doubles per attempt. */
  baseDelayMs?: number;
  /** Backoff ceiling in ms. */
  maxDelayMs?: number;
  /** Recycle the socket when nothing has arrived for this long. */
  idleTimeoutMs?: number;
}

export interface ControlTowerSocket {
  status: ConnectionStatus;
  reconnectAttempts: number;
  lastFrameAt: string | null;
  lastError: string | null;
  /** Force an immediate reconnect (the "Reconnect" action on the connection indicator). */
  reconnect: () => void;
}

const DEFAULTS = {
  baseDelayMs: 500,
  maxDelayMs: 30_000,
  idleTimeoutMs: 45_000,
} as const;

/** Exponential backoff with full jitter, capped. Attempt is 0-based. */
export function backoffDelay(attempt: number, baseDelayMs: number, maxDelayMs: number): number {
  const exponential = Math.min(baseDelayMs * 2 ** attempt, maxDelayMs);
  return Math.round(exponential / 2 + Math.random() * (exponential / 2));
}

/** `ws://host/ws/control-tower` + `?merchantId=`. */
export function controlTowerUrl(merchantId: string): string {
  const base = config.wsUrl;
  const separator = base.includes('?') ? '&' : '?';
  return `${base}${separator}merchantId=${encodeURIComponent(merchantId)}`;
}

export function useControlTowerSocket(options: ControlTowerSocketOptions): ControlTowerSocket {
  const {
    merchantId,
    enabled = true,
    baseDelayMs = DEFAULTS.baseDelayMs,
    maxDelayMs = DEFAULTS.maxDelayMs,
    idleTimeoutMs = DEFAULTS.idleTimeoutMs,
  } = options;

  const status = useLiveStore((state) => state.status);
  const reconnectAttempts = useLiveStore((state) => state.reconnectAttempts);
  const lastFrameAt = useLiveStore((state) => state.lastFrameAt);
  const lastError = useLiveStore((state) => state.lastError);

  const socketRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const watchdogRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const attemptRef = useRef(0);
  const closedByUsRef = useRef(false);
  const mockStopRef = useRef<(() => void) | null>(null);
  /** Bumped by `reconnect()` to re-run the connect effect. */
  const manualNonceRef = useRef(0);
  const forceRef = useRef<(() => void) | null>(null);

  const clearTimers = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    if (watchdogRef.current) {
      clearInterval(watchdogRef.current);
      watchdogRef.current = null;
    }
  }, []);

  useEffect(() => {
    const store = useLiveStore.getState();
    store.setMerchantId(merchantId);

    if (!enabled || !merchantId) {
      store.setStatus('idle');
      return () => undefined;
    }

    /* ---- mock mode: a deterministic in-process feed, no network ---- */
    if (config.useMocks) {
      let cancelled = false;
      store.setStatus('mock');
      void import('@/mocks/socket').then(({ startMockSocket }) => {
        if (cancelled) return;
        mockStopRef.current = startMockSocket(merchantId, (frame) => {
          useLiveStore.getState().pushFrame(frame);
        });
      });
      return () => {
        cancelled = true;
        mockStopRef.current?.();
        mockStopRef.current = null;
        useLiveStore.getState().setStatus('idle');
      };
    }

    /* ---- real socket ---- */
    let disposed = false;

    const scheduleReconnect = () => {
      if (disposed) return;
      const delay = backoffDelay(attemptRef.current, baseDelayMs, maxDelayMs);
      attemptRef.current += 1;
      useLiveStore.getState().noteReconnectAttempt();
      reconnectTimerRef.current = setTimeout(connect, delay);
    };

    function connect() {
      if (disposed || !merchantId) return;
      clearTimers();
      closedByUsRef.current = false;

      const current = useLiveStore.getState();
      current.setStatus(attemptRef.current === 0 ? 'connecting' : 'reconnecting');

      let socket: WebSocket;
      try {
        socket = new WebSocket(controlTowerUrl(merchantId));
      } catch (error) {
        current.setStatus('error', {
          error: error instanceof Error ? error.message : 'WebSocket construction failed',
        });
        scheduleReconnect();
        return;
      }
      socketRef.current = socket;

      socket.onopen = () => {
        attemptRef.current = 0;
        useLiveStore.getState().markConnected();

        // Half-open connections look healthy; recycle when the server goes quiet.
        watchdogRef.current = setInterval(() => {
          const { lastFrameAt: seenAt, connectedAt } = useLiveStore.getState();
          const reference = seenAt ?? connectedAt;
          if (!reference) return;
          if (Date.now() - new Date(reference).getTime() > idleTimeoutMs) {
            closedByUsRef.current = true;
            socket.close(4000, 'idle timeout');
            useLiveStore.getState().setStatus('reconnecting', {
              error: 'no frames received; recycling the socket',
            });
            scheduleReconnect();
          }
        }, Math.max(5_000, Math.floor(idleTimeoutMs / 3)));
      };

      socket.onmessage = (message: MessageEvent<string>) => {
        const result = parseWsFrame(message.data);
        if (!result.ok) {
          // Drop it, but keep the connection: one bad frame is not a dead socket.
          if (process.env.NODE_ENV !== 'production') {
            console.warn('[pdei] dropped WebSocket frame:', result.reason);
          }
          return;
        }
        useLiveStore.getState().pushFrame(result.frame);
      };

      socket.onerror = () => {
        useLiveStore.getState().setStatus('error', { error: 'WebSocket error' });
      };

      socket.onclose = (event: CloseEvent) => {
        if (watchdogRef.current) {
          clearInterval(watchdogRef.current);
          watchdogRef.current = null;
        }
        if (disposed) return;
        if (closedByUsRef.current) {
          closedByUsRef.current = false;
          return;
        }
        useLiveStore.getState().setStatus('closed', {
          error: event.reason || `socket closed (${event.code})`,
        });
        scheduleReconnect();
      };
    }

    forceRef.current = () => {
      attemptRef.current = 0;
      closedByUsRef.current = true;
      socketRef.current?.close(4001, 'manual reconnect');
      socketRef.current = null;
      connect();
    };

    connect();

    return () => {
      disposed = true;
      clearTimers();
      forceRef.current = null;
      const socket = socketRef.current;
      socketRef.current = null;
      if (socket && socket.readyState <= WebSocket.OPEN) {
        closedByUsRef.current = true;
        socket.close(1000, 'component unmounted');
      }
      useLiveStore.getState().setStatus('idle');
    };
    // manualNonceRef is a ref: `reconnect()` calls forceRef directly rather than re-running.
  }, [merchantId, enabled, baseDelayMs, maxDelayMs, idleTimeoutMs, clearTimers]);

  const reconnect = useCallback(() => {
    manualNonceRef.current += 1;
    forceRef.current?.();
  }, []);

  return { status, reconnectAttempts, lastFrameAt, lastError, reconnect };
}
