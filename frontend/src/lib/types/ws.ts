/**
 * Control-tower WebSocket frames - contract 8.1 streaming envelope:
 *   { "type": ..., "at": iso8601, "merchantId": "...", "data": {} }
 *
 * Frames are a *notification* channel: they carry just enough to invalidate the right
 * TanStack Query keys and to render the live tail. Authoritative state always comes from REST.
 */
import type { Iso8601, Money } from './common';
import type { CaseStatus } from './case';
import type { ChaosType } from './simulation';
import type { DisputeReasonCode, DisputeStatus } from './dispute';
import type { EvidenceStatus, EvidenceType } from './evidence';
import type { GapSeverity, GapType, ReadinessBand } from './readiness';

export type WsFrameType =
  | 'READINESS_UPDATED'
  | 'EVIDENCE_ADDED'
  | 'DISPUTE_CREATED'
  | 'CASE_UPDATED'
  | 'GAP_DETECTED'
  | 'CHAOS_INJECTED'
  | 'HEARTBEAT';

export const WS_FRAME_TYPES: readonly WsFrameType[] = [
  'READINESS_UPDATED',
  'EVIDENCE_ADDED',
  'DISPUTE_CREATED',
  'CASE_UPDATED',
  'GAP_DETECTED',
  'CHAOS_INJECTED',
  'HEARTBEAT',
] as const;

export interface WsEnvelope<TType extends WsFrameType, TData> {
  type: TType;
  at: Iso8601;
  merchantId: string;
  data: TData;
}

export interface ReadinessUpdatedData {
  transactionId: string;
  score: number;
  band: ReadinessBand;
  previousScore: number | null;
  previousBand: ReadinessBand | null;
  reasonCode: DisputeReasonCode | null;
  computedAt: Iso8601;
}

export interface EvidenceAddedData {
  evidenceId: string;
  transactionId: string;
  type: EvidenceType;
  status: EvidenceStatus;
  version: number;
  sha256: string;
}

export interface DisputeCreatedData {
  disputeId: string;
  transactionId: string;
  reasonCode: DisputeReasonCode;
  status: DisputeStatus;
  amount: Money;
  deadlineAt: Iso8601 | null;
}

export interface CaseUpdatedData {
  caseId: string;
  disputeId: string;
  transactionId: string;
  status: CaseStatus;
  previousStatus: CaseStatus | null;
  /** Set when the workflow is parked on a human decision. */
  awaitingApproval: boolean;
}

/**
 * `GAP_DETECTED` body.
 *
 * `transactionId` is the ONLY field a real frame is guaranteed to carry. readiness-worker's
 * `ReadinessEventPublisher.publishGapDetected` nests the gap rows inside a `gaps` array and puts
 * only snapshotId/transactionId/score/band/worstSeverity at the payload top level, and the
 * gateway's `StreamFrame.from()` then lifts only its whitelist (transactionId, score, band,
 * severity, gapType, ...). So gapId, type, severity, evidenceType and detail are best-effort:
 * render them defensively and never gate the frame on them.
 *
 * That is by design - a frame is a notification, not state. The authoritative gap list is
 * `GET /gaps`, which this frame invalidates.
 */
export interface GapDetectedData {
  transactionId: string;
  /** Readiness score of the snapshot the gaps were detected on. */
  score?: number;
  band?: ReadinessBand;
  gapId?: string;
  type?: GapType;
  severity?: GapSeverity;
  evidenceType?: EvidenceType | null;
  detail?: string | null;
}

export interface ChaosInjectedData {
  injectionId: string;
  type: ChaosType;
  target: Record<string, unknown>;
  runId: string | null;
}

/** Server liveness ping. Carries no payload; resets the client watchdog. */
export type HeartbeatData = Record<string, never>;

export type ReadinessUpdatedFrame = WsEnvelope<'READINESS_UPDATED', ReadinessUpdatedData>;
export type EvidenceAddedFrame = WsEnvelope<'EVIDENCE_ADDED', EvidenceAddedData>;
export type DisputeCreatedFrame = WsEnvelope<'DISPUTE_CREATED', DisputeCreatedData>;
export type CaseUpdatedFrame = WsEnvelope<'CASE_UPDATED', CaseUpdatedData>;
export type GapDetectedFrame = WsEnvelope<'GAP_DETECTED', GapDetectedData>;
export type ChaosInjectedFrame = WsEnvelope<'CHAOS_INJECTED', ChaosInjectedData>;
export type HeartbeatFrame = WsEnvelope<'HEARTBEAT', HeartbeatData>;

/** Discriminated union of every server -> client frame. */
export type WsFrame =
  | ReadinessUpdatedFrame
  | EvidenceAddedFrame
  | DisputeCreatedFrame
  | CaseUpdatedFrame
  | GapDetectedFrame
  | ChaosInjectedFrame
  | HeartbeatFrame;

/** A frame as stored in the live tail: the wire frame plus client-side receipt metadata. */
export interface LiveEvent<T extends WsFrame = WsFrame> {
  /** Client-assigned, monotonic within a session. The React key for tail rows. */
  seq: number;
  receivedAt: Iso8601;
  frame: T;
}

export type ConnectionStatus =
  | 'idle'
  | 'connecting'
  | 'open'
  | 'reconnecting'
  | 'closed'
  | 'error'
  | 'mock';

/** Human labels for the connection indicator. Colour alone never carries the state. */
export const CONNECTION_LABEL: Readonly<Record<ConnectionStatus, string>> = {
  idle: 'Not connected',
  connecting: 'Connecting',
  open: 'Live',
  reconnecting: 'Reconnecting',
  closed: 'Disconnected',
  error: 'Connection error',
  mock: 'Mock feed',
} as const;
