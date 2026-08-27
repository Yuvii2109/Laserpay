/**
 * Readiness domain types - contract 6 and 7, mirrors `core.model.ReadinessSnapshot`,
 * `core.model.ReadinessGap`, `core.model.RequirementView`, `core.model.ContradictionView`.
 */
import type { Iso8601 } from './common';
import type { EvidenceType } from './evidence';
import type { DisputeReasonCode } from './dispute';

export type ReadinessBand = 'READY' | 'NEARLY_READY' | 'AT_RISK' | 'NOT_READY';

export const READINESS_BANDS: readonly ReadinessBand[] = [
  'READY',
  'NEARLY_READY',
  'AT_RISK',
  'NOT_READY',
] as const;

export type RequirementStrength = 'MANDATORY' | 'RECOMMENDED' | 'OPTIONAL' | 'PROHIBITED';

export const REQUIREMENT_STRENGTHS: readonly RequirementStrength[] = [
  'MANDATORY',
  'RECOMMENDED',
  'OPTIONAL',
  'PROHIBITED',
] as const;

/** Default scoring weights - contract 7 (`RequirementStrength.weight()`). */
export const REQUIREMENT_WEIGHT: Readonly<Record<RequirementStrength, number>> = {
  MANDATORY: 3,
  RECOMMENDED: 2,
  OPTIONAL: 1,
  PROHIBITED: 0,
} as const;

export type GapType =
  | 'MISSING'
  | 'EXPIRED'
  | 'EXPIRING_SOON'
  | 'CONTRADICTORY'
  | 'UNVERIFIABLE_PROVENANCE'
  | 'LOW_QUALITY'
  | 'VERSION_CONFLICT';

export const GAP_TYPES: readonly GapType[] = [
  'MISSING',
  'EXPIRED',
  'EXPIRING_SOON',
  'CONTRADICTORY',
  'UNVERIFIABLE_PROVENANCE',
  'LOW_QUALITY',
  'VERSION_CONFLICT',
] as const;

export type GapSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export const GAP_SEVERITIES: readonly GapSeverity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;

/**
 * Band thresholds - contract 6. Kept here so the UI never re-derives them by hand;
 * `bandFromScore()` in lib/format/score.ts is the only place that maps score to band.
 */
export const READINESS_BAND_MIN_SCORE: Readonly<Record<ReadinessBand, number>> = {
  READY: 90,
  NEARLY_READY: 75,
  AT_RISK: 50,
  NOT_READY: 0,
} as const;

/** One requirement row of a snapshot - mirrors `core.model.RequirementView`. */
export interface RequirementView {
  type: EvidenceType;
  strength: RequirementStrength;
  satisfied: boolean;
  satisfyingEvidenceIds: string[];
  weight: number;
  note: string | null;
}

/** One detected gap - mirrors `core.model.ReadinessGap`. */
export interface ReadinessGap {
  gapId: string;
  transactionId: string;
  type: GapType;
  evidenceType: EvidenceType | null;
  severity: GapSeverity;
  evidenceId: string | null;
  detail: string | null;
  detectedAt: Iso8601;
  expiresAt: Iso8601 | null;
}

/** A cross-evidence field conflict - mirrors `core.model.ContradictionView`. */
export interface ContradictionView {
  left: string | null;
  right: string | null;
  field: string | null;
  detail: string | null;
  severity: GapSeverity;
  leftValue: string | null;
  rightValue: string | null;
  detectedAt: Iso8601 | null;
}

/**
 * `GET /transactions/{id}/readiness` - mirrors `core.model.ReadinessSnapshot`.
 * `score` is an integer 0-100; `baseScore` and `penaltyPoints` expose the contract 7 formula
 * so the UI can show the arithmetic instead of asserting it.
 */
export interface ReadinessSnapshot {
  snapshotId: string;
  transactionId: string;
  merchantId: string;
  reasonCode: DisputeReasonCode | null;
  score: number;
  band: ReadinessBand;
  baseScore: number;
  penaltyPoints: number;
  requirements: RequirementView[];
  gaps: ReadinessGap[];
  contradictions: ContradictionView[];
  policyVersionId: string | null;
  computedAt: Iso8601;
}

/** Query shape of `GET /gaps` (the at-risk feed). */
export interface GapQuery {
  merchantId?: string;
  type?: GapType;
  severity?: GapSeverity;
  page?: number;
  size?: number;
}

/**
 * A `GET /gaps` row. The gateway returns `PageResponse<ReadinessGap>` (GapController), so a row
 * is exactly `core.model.ReadinessGap` and carries no transaction context of its own: no
 * merchantId, no readiness score or band, no disputeId. `transactionId` is the join key - the
 * at-risk feed reads the band from `GET /transactions/{transactionId}/readiness`.
 */
export type GapFeedItem = ReadinessGap;
