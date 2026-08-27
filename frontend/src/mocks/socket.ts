/**
 * In-process stand-in for the control-tower WebSocket.
 *
 * `useControlTowerSocket` calls `startMockSocket` when NEXT_PUBLIC_USE_MOCKS is true, so the
 * connection indicator, the live tail and the query-invalidation path are all exercised with
 * no gateway running. Frames are drawn from the same fixture dataset the REST mocks serve, so
 * a READINESS_UPDATED frame names a transaction that actually exists.
 *
 * The feed is deliberately unruly: it emits heartbeats, occasional duplicates and the
 * occasional out-of-order timestamp, because those are the conditions the real consumers
 * promise to tolerate (contract 17 rules 9 and 10).
 */
import type { WsFrame } from '@/lib/types/ws';
import { mockDataset } from './dataset';
import { createRng } from './random';

const HEARTBEAT_INTERVAL_MS = 15_000;
const EVENT_INTERVAL_MS = 4_000;

export type FrameSink = (frame: WsFrame) => void;

/**
 * Starts the feed for one merchant. Returns a stop function; calling it twice is safe.
 */
export function startMockSocket(merchantId: string, sink: FrameSink): () => void {
  const rng = createRng(merchantId.split('').reduce((acc, ch) => acc + ch.charCodeAt(0), 7));
  const data = mockDataset;

  const transactions = data.transactions.filter((item) => item.merchantId === merchantId);
  const evidence = Object.values(data.evidenceById).filter((item) => item.merchantId === merchantId);
  const disputes = data.disputes.filter((item) => item.merchantId === merchantId);
  const cases = data.cases.filter((item) => item.merchantId === merchantId);
  const gaps = data.gapsByMerchant[merchantId] ?? [];

  let lastFrame: WsFrame | null = null;

  const nextFrame = (): WsFrame | null => {
    const at = new Date(
      // Occasionally stamp a frame slightly in the past: late events are normal here.
      Date.now() - (rng.chance(0.15) ? rng.int(2_000, 20_000) : 0),
    ).toISOString();

    const roll = rng.next();

    if (roll < 0.34 && transactions.length > 0) {
      const transaction = rng.pick(transactions);
      const previousScore = transaction.readinessScore;
      const delta = rng.int(-12, 14);
      const score = Math.max(0, Math.min(100, (previousScore ?? 50) + delta));
      const band =
        score >= 90 ? 'READY' : score >= 75 ? 'NEARLY_READY' : score >= 50 ? 'AT_RISK' : 'NOT_READY';
      return {
        type: 'READINESS_UPDATED',
        at,
        merchantId,
        data: {
          transactionId: transaction.transactionId,
          score,
          band,
          previousScore: previousScore ?? null,
          previousBand: transaction.readinessBand,
          reasonCode: null,
          computedAt: at,
        },
      };
    }

    if (roll < 0.6 && evidence.length > 0) {
      const item = rng.pick(evidence);
      return {
        type: 'EVIDENCE_ADDED',
        at,
        merchantId,
        data: {
          evidenceId: item.evidenceId,
          transactionId: item.transactionId,
          type: item.type,
          status: item.status,
          version: item.version,
          sha256: item.sha256,
        },
      };
    }

    if (roll < 0.75 && gaps.length > 0) {
      const gap = rng.pick(gaps);
      const snapshot = data.readinessByTransaction[gap.transactionId];
      // Exactly what StreamFrame.from() can lift out of a ReadinessGapDetected payload: the
      // transaction and the snapshot headline. gapId is nested inside `gaps` and never reaches
      // the frame, so the mock does not pretend otherwise.
      return {
        type: 'GAP_DETECTED',
        at,
        merchantId,
        data: {
          transactionId: gap.transactionId,
          ...(snapshot ? { score: snapshot.score, band: snapshot.band } : {}),
        },
      };
    }

    if (roll < 0.9 && cases.length > 0) {
      const item = rng.pick(cases);
      return {
        type: 'CASE_UPDATED',
        at,
        merchantId,
        data: {
          caseId: item.caseId,
          disputeId: item.disputeId,
          transactionId: item.transactionId,
          status: item.status,
          previousStatus: null,
          awaitingApproval: item.status === 'AWAITING_APPROVAL',
        },
      };
    }

    if (disputes.length > 0) {
      const dispute = rng.pick(disputes);
      return {
        type: 'DISPUTE_CREATED',
        at,
        merchantId,
        data: {
          disputeId: dispute.disputeId,
          transactionId: dispute.transactionId,
          reasonCode: dispute.reasonCode,
          status: dispute.status,
          amount: dispute.amount,
          deadlineAt: dispute.deadlineAt,
        },
      };
    }

    return null;
  };

  const eventTimer = setInterval(() => {
    // One frame in eight is a redelivery of the previous one - the store must drop it.
    if (lastFrame && rng.chance(0.12)) {
      sink(lastFrame);
      return;
    }
    const frame = nextFrame();
    if (!frame) return;
    lastFrame = frame;
    sink(frame);
  }, EVENT_INTERVAL_MS);

  const heartbeatTimer = setInterval(() => {
    sink({ type: 'HEARTBEAT', at: new Date().toISOString(), merchantId, data: {} });
  }, HEARTBEAT_INTERVAL_MS);

  // Send one heartbeat immediately so the indicator turns live without waiting.
  sink({ type: 'HEARTBEAT', at: new Date().toISOString(), merchantId, data: {} });

  let stopped = false;
  return () => {
    if (stopped) return;
    stopped = true;
    clearInterval(eventTimer);
    clearInterval(heartbeatTimer);
  };
}
