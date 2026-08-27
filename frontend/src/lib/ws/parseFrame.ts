/**
 * WebSocket frame parsing. The envelope is validated with zod before anything reaches the
 * live store: a malformed frame is dropped, never thrown, because a bad frame from a
 * mid-deploy gateway must not take the console down.
 *
 * Envelope (contract 8.1):
 *   { "type": "...", "at": iso8601, "merchantId": "...", "data": {} }
 */
import { z } from 'zod';
import { WS_FRAME_TYPES, type WsFrame, type WsFrameType } from '@/lib/types/ws';

const frameTypeSchema = z.enum(
  WS_FRAME_TYPES as unknown as [WsFrameType, ...WsFrameType[]],
);

const envelopeSchema = z.object({
  type: frameTypeSchema,
  at: z.string().min(1),
  merchantId: z.string().default(''),
  data: z.record(z.unknown()).nullish().transform((value) => value ?? {}),
});

/**
 * Business ids each frame type must carry for the tail and the invalidation map to work.
 *
 * Only ids the gateway's `StreamFrame.from()` whitelist can actually produce belong here. A
 * GAP_DETECTED frame carries `transactionId` and nothing else reliably - readiness-worker nests
 * `gapId` inside the payload's `gaps` array, so requiring it here silently discarded every gap
 * frame and the at-risk feed never refreshed. `transactionId` is enough to invalidate.
 */
const REQUIRED_DATA_FIELDS: Readonly<Record<WsFrameType, readonly string[]>> = {
  READINESS_UPDATED: ['transactionId'],
  EVIDENCE_ADDED: ['evidenceId', 'transactionId'],
  DISPUTE_CREATED: ['disputeId', 'transactionId'],
  CASE_UPDATED: ['caseId'],
  GAP_DETECTED: ['transactionId'],
  CHAOS_INJECTED: ['injectionId'],
  HEARTBEAT: [],
};

export interface ParseFailure {
  ok: false;
  reason: string;
}

export interface ParseSuccess {
  ok: true;
  frame: WsFrame;
}

export type ParseResult = ParseSuccess | ParseFailure;

/** Parses one raw socket message. Never throws. */
export function parseWsFrame(raw: unknown): ParseResult {
  let candidate: unknown = raw;

  if (typeof raw === 'string') {
    try {
      candidate = JSON.parse(raw);
    } catch {
      return { ok: false, reason: 'frame is not valid JSON' };
    }
  }

  const parsed = envelopeSchema.safeParse(candidate);
  if (!parsed.success) {
    return { ok: false, reason: parsed.error.issues[0]?.message ?? 'envelope does not match the contract' };
  }

  const { type, data } = parsed.data;
  for (const field of REQUIRED_DATA_FIELDS[type]) {
    if (typeof data[field] !== 'string' || (data[field] as string).length === 0) {
      return { ok: false, reason: `${type} frame is missing data.${field}` };
    }
  }

  // The envelope is verified; `data` is typed by the discriminated union in types/ws.ts.
  return { ok: true, frame: parsed.data as unknown as WsFrame };
}

/** Convenience for call sites that only care about the happy path. */
export function tryParseWsFrame(raw: unknown): WsFrame | null {
  const result = parseWsFrame(raw);
  return result.ok ? result.frame : null;
}
