/**
 * Platform metrics types - contract 8.1 `GET /metrics/funnel`.
 * Mirrors `core.model.FunnelMetrics`.
 */
import type { Iso8601 } from './common';

/**
 * events -> candidates -> ambiguous -> AI -> human.
 * The funnel is the cost story: only ambiguity should reach the model (contract 9.4).
 */
export interface FunnelMetrics {
  merchantId: string | null;
  from: Iso8601;
  to: Iso8601;
  events: number;
  candidates: number;
  ambiguous: number;
  aiInvestigated: number;
  humanReviewed: number;
  autoPrepared: number;
  denied: number;
}

/** One rung of the ramp, as the server computed it. */
export interface FunnelStage {
  name: string;
  count: number;
  /** Share of the previous stage that reached this one, in [0,1]. */
  conversionFromPrevious: number;
}

/**
 * What `GET /metrics/funnel` actually returns (contract 8.1).
 *
 * The counters are nested under `metrics`; `stages` and the two rates are derived server-side
 * so every client draws the same ramp from the same arithmetic. This type used to be declared
 * as a bare `FunnelMetrics`, so every read went through the wrong level of the object and came
 * back `undefined` - a `200` response and a dead page.
 */
export interface FunnelResponse {
  metrics: FunnelMetrics;
  stages: FunnelStage[];
  aiAdmissionRate: number;
  autoPrepareRate: number;
}

export interface FunnelQuery {
  merchantId?: string;
  from?: Iso8601;
  to?: Iso8601;
}

/** Share of candidates actually sent to the model, in [0,1]. */
export function aiAdmissionRate(m: FunnelMetrics): number {
  return m.candidates <= 0 ? 0 : m.aiInvestigated / m.candidates;
}

/** Share of candidates resolved with no human touch, in [0,1]. */
export function autoPrepareRate(m: FunnelMetrics): number {
  return m.candidates <= 0 ? 0 : m.autoPrepared / m.candidates;
}

/** Ordered funnel stages for the observability page; render as one ordinal ramp. */
export const FUNNEL_STAGES: readonly { key: keyof FunnelMetrics; label: string }[] = [
  { key: 'events', label: 'Events ingested' },
  { key: 'candidates', label: 'Dispute candidates' },
  { key: 'ambiguous', label: 'Ambiguous' },
  { key: 'aiInvestigated', label: 'Sent to AI' },
  { key: 'humanReviewed', label: 'Human reviewed' },
] as const;
