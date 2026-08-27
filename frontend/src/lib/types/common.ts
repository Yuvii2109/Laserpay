/**
 * Primitive shapes shared by every DTO. Mirrors `com.laserpay.pdei.common` and
 * `com.laserpay.pdei.core.model` (docs/SHARED-LIBRARY-API.md 4 cross-language parity).
 */

/** ISO-8601 instant, always UTC. Never a local date-time. */
export type Iso8601 = string;

/**
 * Money - contract 5 money rule. `amountMinor` is an integer count of minor units and
 * `currency` an ISO-4217 alpha-3 code. NEVER divide this by a hardcoded 100:
 * render through `formatMoney()` / `<MoneyDisplay />`, which honour the currency exponent.
 */
export interface Money {
  amountMinor: number;
  currency: string;
}

/**
 * Page envelope returned by every list route of the gateway.
 * Mirrors `com.laserpay.pdei.api.dto.PageResponse<T>` field for field - the gateway wraps
 * evidence-core's `SearchPage` rather than returning it, and the field names are fixed:
 * `content`, `page`, `size`, `totalElements`, `totalPages`. `page` is zero-based.
 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * The evidence-core envelope `core.model.SearchPage<T>`. NOT what a REST route returns:
 * the gateway always re-wraps it as {@link PageResponse}. Kept for parity only.
 */
export interface SearchPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface PageParams {
  page?: number;
  size?: number;
}

/** Server error body - mirrors `common.error.ErrorResponse`. */
export interface ErrorResponseBody {
  code: string;
  message: string;
  correlationId: string | null;
  at: Iso8601;
  details: Record<string, unknown>;
}

/**
 * Normalised client-side error. Every failure out of `lib/api/client.ts` is one of these,
 * including transport failures (status 0) and mock-mode misses (status 404).
 */
export interface ApiError extends Error {
  readonly name: 'ApiError';
  /** HTTP status, or 0 when the request never reached the gateway. */
  readonly status: number;
  /** `ErrorResponse.code` when the gateway supplied one, else a synthetic code. */
  readonly code: string;
  readonly correlationId: string | null;
  readonly at: Iso8601;
  readonly details: Record<string, unknown>;
  readonly url: string;
  readonly method: string;
  readonly retryable: boolean;
}

/** Actor of an audited action - mirrors `common.event.ActorType`. */
export type ActorType = 'SYSTEM' | 'MERCHANT_USER' | 'OPERATOR' | 'AI_SERVICE' | 'SIMULATOR';

export const ACTOR_TYPES: readonly ActorType[] = [
  'SYSTEM',
  'MERCHANT_USER',
  'OPERATOR',
  'AI_SERVICE',
  'SIMULATOR',
] as const;

/** Human-readable id prefixes - contract 5. */
export const ID_PREFIX = {
  MERCHANT: 'MER-',
  CUSTOMER: 'CUS-',
  TRANSACTION: 'TX-',
  PAYMENT: 'PAY-',
  ORDER: 'ORD-',
  SHIPMENT: 'SHP-',
  DELIVERY: 'DLV-',
  REFUND: 'REF-',
  COMMUNICATION: 'COM-',
  EVIDENCE: 'EV-',
  POLICY: 'POL-',
  DISPUTE: 'DSP-',
  CASE: 'CASE-',
  INVESTIGATION: 'INV-',
  AUDIT: 'AUD-',
  SIMULATION: 'SIM-',
} as const;

export type IdPrefix = (typeof ID_PREFIX)[keyof typeof ID_PREFIX];

/** Sort direction used by DataTable and by list routes that accept `sort`. */
export type SortDirection = 'asc' | 'desc';

/** A `{ from, to }` instant window; both bounds inclusive, both optional. */
export interface TimeRange {
  from?: Iso8601;
  to?: Iso8601;
}
