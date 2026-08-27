/**
 * The bridge between the live socket and server state.
 *
 * A WebSocket frame never carries authoritative domain state - it says "something changed".
 * This hook turns each frame into the set of query keys that are now stale and invalidates
 * exactly those, so TanStack Query re-fetches the truth over REST.
 *
 * Mount it once, in the app shell. Mounting it twice is harmless (invalidation is idempotent)
 * but wasteful.
 */
'use client';

import { useEffect, useRef } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useLiveStore } from '@/lib/store/liveStore';
import { queryKeys } from './keys';
import type { WsFrame } from '@/lib/types/ws';

/**
 * Keys invalidated by one frame. Exported so a page can reuse the same mapping after a
 * mutation, and so the mapping is testable without a socket.
 */
export function keysForFrame(frame: WsFrame): (readonly unknown[])[] {
  switch (frame.type) {
    case 'READINESS_UPDATED': {
      const { transactionId } = frame.data;
      return [
        queryKeys.transactions.detail(transactionId),
        queryKeys.transactions.readiness(transactionId),
        queryKeys.transactions.list(),
        queryKeys.gaps.all(),
        queryKeys.merchants.summary(frame.merchantId),
      ];
    }
    case 'EVIDENCE_ADDED': {
      const { evidenceId, transactionId } = frame.data;
      return [
        queryKeys.evidence.detail(evidenceId),
        queryKeys.evidence.versions(evidenceId),
        queryKeys.evidence.lineage(evidenceId),
        queryKeys.evidence.all(),
        queryKeys.transactions.evidence(transactionId),
        queryKeys.transactions.graph(transactionId),
        queryKeys.transactions.timeline(transactionId),
        queryKeys.transactions.readiness(transactionId),
        queryKeys.merchants.summary(frame.merchantId),
      ];
    }
    case 'DISPUTE_CREATED': {
      const { disputeId, transactionId } = frame.data;
      return [
        queryKeys.disputes.detail(disputeId),
        queryKeys.disputes.all(),
        queryKeys.transactions.detail(transactionId),
        queryKeys.transactions.timeline(transactionId),
        queryKeys.cases.all(),
        queryKeys.merchants.summary(frame.merchantId),
      ];
    }
    case 'CASE_UPDATED': {
      const { caseId, disputeId, transactionId } = frame.data;
      return [
        queryKeys.cases.detail(caseId),
        queryKeys.cases.xray(caseId),
        queryKeys.cases.packageManifest(caseId),
        queryKeys.cases.all(),
        queryKeys.disputes.detail(disputeId),
        queryKeys.transactions.timeline(transactionId),
        queryKeys.investigations.all(),
        queryKeys.metrics.all(),
        queryKeys.merchants.summary(frame.merchantId),
      ];
    }
    case 'GAP_DETECTED': {
      const { transactionId } = frame.data;
      return [
        queryKeys.gaps.all(),
        queryKeys.transactions.readiness(transactionId),
        queryKeys.transactions.detail(transactionId),
        queryKeys.merchants.summary(frame.merchantId),
      ];
    }
    case 'CHAOS_INJECTED':
      // Chaos can touch anything: refresh the simulation console and the merchant KPIs, and
      // let the frames that follow invalidate the specific entities it damaged.
      return [
        queryKeys.simulation.all(),
        queryKeys.merchants.summary(frame.merchantId),
        queryKeys.audit.all(),
      ];
    case 'HEARTBEAT':
    default:
      return [];
  }
}

function invalidate(client: QueryClient, frame: WsFrame): void {
  for (const queryKey of keysForFrame(frame)) {
    void client.invalidateQueries({ queryKey });
  }
}

/**
 * Subscribes to the live store and invalidates as frames arrive. Frames are consumed by
 * sequence number so a burst is processed exactly once even if React batches renders.
 */
export function useInvalidateOnWsEvent(enabled = true): void {
  const queryClient = useQueryClient();
  const lastSeqRef = useRef(0);

  useEffect(() => {
    if (!enabled) return;

    // Catch up on anything already in the tail before subscribing.
    const initial = useLiveStore.getState().events;
    for (const event of [...initial].reverse()) {
      if (event.seq > lastSeqRef.current) {
        invalidate(queryClient, event.frame);
        lastSeqRef.current = event.seq;
      }
    }

    return useLiveStore.subscribe((state) => {
      const pending = state.events.filter((event) => event.seq > lastSeqRef.current);
      if (pending.length === 0) return;
      // `events` is newest-first; replay oldest-first so invalidation order matches arrival.
      for (const event of [...pending].reverse()) {
        invalidate(queryClient, event.frame);
        lastSeqRef.current = Math.max(lastSeqRef.current, event.seq);
      }
    });
  }, [enabled, queryClient]);
}
