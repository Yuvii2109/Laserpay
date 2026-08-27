/**
 * Readiness scoring presentation: band derivation, labels, and the reserved status colours.
 *
 * Colour never carries state on its own here - every helper returns a label alongside its
 * token, and the badge/meter components render both. The four bands map onto the four-step
 * status ramp (good / warning / serious / critical) declared in globals.css.
 */
import type { GapSeverity, ReadinessBand } from '@/lib/types/readiness';
import { READINESS_BAND_MIN_SCORE } from '@/lib/types/readiness';
import type { CaseStatus } from '@/lib/types/case';
import type { DisputeStatus } from '@/lib/types/dispute';
import type { EvidenceStatus } from '@/lib/types/evidence';
import type { SafetyDecision } from '@/lib/types/ai';

/** Semantic tone shared by every status surface. `neutral` is the no-signal default. */
export type Tone = 'good' | 'warning' | 'serious' | 'critical' | 'neutral' | 'info';

/** Contract 6 band thresholds. The single place a score becomes a band. */
export function bandFromScore(score: number | null | undefined): ReadinessBand | null {
  if (score === null || score === undefined || !Number.isFinite(score)) return null;
  if (score >= READINESS_BAND_MIN_SCORE.READY) return 'READY';
  if (score >= READINESS_BAND_MIN_SCORE.NEARLY_READY) return 'NEARLY_READY';
  if (score >= READINESS_BAND_MIN_SCORE.AT_RISK) return 'AT_RISK';
  return 'NOT_READY';
}

export const BAND_LABEL: Readonly<Record<ReadinessBand, string>> = {
  READY: 'Ready',
  NEARLY_READY: 'Nearly ready',
  AT_RISK: 'At risk',
  NOT_READY: 'Not ready',
};

/** One-line explanation shown in tooltips beside a band badge. */
export const BAND_DESCRIPTION: Readonly<Record<ReadinessBand, string>> = {
  READY: 'Score 90-100. Every mandatory requirement is satisfied; a representment can be prepared.',
  NEARLY_READY: 'Score 75-89. Minor gaps only; close them before a dispute lands.',
  AT_RISK: 'Score 50-74. Mandatory evidence is missing, expiring or contradicted.',
  NOT_READY: 'Score below 50. This transaction cannot currently be defended.',
};

export const BAND_TONE: Readonly<Record<ReadinessBand, Tone>> = {
  READY: 'good',
  NEARLY_READY: 'warning',
  AT_RISK: 'serious',
  NOT_READY: 'critical',
};

/** CSS variable carrying the band colour. Use with `style={{ color: bandColorVar(band) }}`. */
export function bandColorVar(band: ReadinessBand | null | undefined): string {
  switch (band) {
    case 'READY':
      return 'var(--status-good)';
    case 'NEARLY_READY':
      return 'var(--status-warning)';
    case 'AT_RISK':
      return 'var(--status-serious)';
    case 'NOT_READY':
      return 'var(--status-critical)';
    default:
      return 'var(--status-neutral)';
  }
}

export function toneColorVar(tone: Tone): string {
  switch (tone) {
    case 'good':
      return 'var(--status-good)';
    case 'warning':
      return 'var(--status-warning)';
    case 'serious':
      return 'var(--status-serious)';
    case 'critical':
      return 'var(--status-critical)';
    case 'info':
      return 'var(--chart-1)';
    default:
      return 'var(--status-neutral)';
  }
}

export const SEVERITY_TONE: Readonly<Record<GapSeverity, Tone>> = {
  LOW: 'neutral',
  MEDIUM: 'warning',
  HIGH: 'serious',
  CRITICAL: 'critical',
};

export const SEVERITY_LABEL: Readonly<Record<GapSeverity, string>> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  CRITICAL: 'Critical',
};

/** Ordering for gap feeds: worst first. */
export const SEVERITY_RANK: Readonly<Record<GapSeverity, number>> = {
  CRITICAL: 0,
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3,
};

export const EVIDENCE_STATUS_TONE: Readonly<Record<EvidenceStatus, Tone>> = {
  PENDING: 'neutral',
  ACTIVE: 'good',
  EXPIRING: 'warning',
  EXPIRED: 'serious',
  INVALIDATED: 'critical',
  SUPERSEDED: 'neutral',
};

export const DISPUTE_STATUS_TONE: Readonly<Record<DisputeStatus, Tone>> = {
  OPEN: 'info',
  EVIDENCE_GATHERING: 'info',
  UNDER_INVESTIGATION: 'info',
  AWAITING_HUMAN_REVIEW: 'warning',
  REPRESENTMENT_PREPARED: 'good',
  SUBMITTED: 'good',
  WON: 'good',
  LOST: 'critical',
  EXPIRED: 'serious',
  WITHDRAWN: 'neutral',
};

export const CASE_STATUS_TONE: Readonly<Record<CaseStatus, Tone>> = {
  CREATED: 'neutral',
  ASSEMBLING: 'info',
  INVESTIGATING: 'info',
  AWAITING_EVIDENCE: 'warning',
  AWAITING_APPROVAL: 'warning',
  PREPARED: 'good',
  SUBMITTED: 'good',
  CLOSED: 'neutral',
  FAILED: 'critical',
};

export const SAFETY_DECISION_TONE: Readonly<Record<SafetyDecision, Tone>> = {
  ALLOW: 'good',
  ALLOW_WITH_REVIEW: 'warning',
  DENY: 'critical',
};

/** `92` -> `92`, null -> `—`. Scores are integers 0-100 by contract 7. */
export function formatScore(score: number | null | undefined): string {
  if (score === null || score === undefined || !Number.isFinite(score)) return '—';
  return String(Math.round(score));
}

/** Confidence in [0,1] -> `97.3%`. AI confidences are shown to one decimal. */
export function formatConfidence(confidence: number | null | undefined): string {
  if (confidence === null || confidence === undefined || !Number.isFinite(confidence)) return '—';
  return `${(confidence * 100).toFixed(1)}%`;
}

/** Fraction of the meter to fill, clamped to [0,1]. */
export function scoreFraction(score: number | null | undefined): number {
  if (score === null || score === undefined || !Number.isFinite(score)) return 0;
  // eslint-disable-next-line no-restricted-syntax -- a 0-100 score to a 0-1 fraction, not money
  return Math.min(Math.max(score, 0), 100) / 100;
}

/**
 * Band boundaries as fractions, for drawing threshold ticks on a meter.
 * Order matches the meter's left-to-right direction.
 */
export const BAND_THRESHOLDS: readonly { band: ReadinessBand; at: number }[] = [
  { band: 'AT_RISK', at: 0.5 },
  { band: 'NEARLY_READY', at: 0.75 },
  { band: 'READY', at: 0.9 },
];
