/**
 * Merchant types - contract 8.1 `/merchants*`. `MerchantView` mirrors `MerchantEntity`;
 * `MerchantSummary` is the control-tower KPI payload of `GET /merchants/{id}/summary`.
 */
import type { Iso8601 } from './common';
import type { ReadinessBand } from './readiness';
import type { CaseStatus } from './case';
import type { EvidenceStatus } from './evidence';

export interface MerchantView {
  merchantId: string;
  legalName: string;
  displayName: string;
  country: string;
  /** ISO-4217 alpha-3. Every Money on this merchant defaults to it. */
  defaultCurrency: string;
  mcc: string | null;
  status: string;
  timezone: string;
  contactEmail: string | null;
  /** Historical representment win rate in basis points (integer), e.g. 7100 = 71.00%. */
  baselineWinRateBps: number | null;
  onboardedAt: Iso8601;
  riskProfile: Record<string, unknown>;
  metadata: Record<string, unknown>;
}

/**
 * `GET /merchants/{merchantId}/summary` - everything the Control Tower needs in one call.
 * Mirrors `api.dto.MerchantSummaryResponse` field for field.
 *
 * Every figure is a count and the shape is flat. There is deliberately NO aggregated money
 * here: summing `amount_minor` across disputes needs a currency-aware aggregate the repository
 * layer does not expose, and one number that silently mixes currencies would be worse than no
 * number (contract 5 money rule). A page that wants exposure must sum a currency at a time from
 * `GET /disputes`.
 */
export interface MerchantSummary {
  merchantId: string;
  displayName: string;
  /** ISO-4217 alpha-3; the merchant's default, not an aggregate currency. */
  defaultCurrency: string;
  /** Total scored transactions - the sum of `readinessDistribution`. */
  transactions: number;
  /** Band-weighted approximation, 0-100. Null when nothing has been scored yet. */
  averageReadinessScore: number | null;
  /** The band holding the most transactions. Null when nothing has been scored yet. */
  dominantBand: ReadinessBand | null;
  /** Transactions per band; every band is present, zero-filled. */
  readinessDistribution: Record<ReadinessBand, number>;
  /** Evidence per status; every status is present, zero-filled. */
  evidenceByStatus: Record<EvidenceStatus, number>;
  /** Cases per status; every status is present, zero-filled. */
  casesByStatus: Record<CaseStatus, number>;
  /** Disputes in a non-terminal status. */
  openDisputes: number;
  /** Transactions in the AT_RISK or NOT_READY band - the at-risk feed count. */
  atRiskTransactions: number;
  /** Evidence in EXPIRING status - the "-5 per expiring mandatory" pipeline. */
  expiringEvidence: number;
  /** Cases in AWAITING_APPROVAL - the human queue depth. */
  casesRequiringReview: number;
  /** Unresolved gaps of HIGH or CRITICAL severity. */
  blockingGaps: number;
  generatedAt: Iso8601;
}

export interface MerchantQuery {
  page?: number;
  size?: number;
  q?: string;
}
