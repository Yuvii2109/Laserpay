/**
 * Dispute domain types - contract 6 and 8.1, mirrors `core.model.DisputeView`.
 */
import type { Iso8601, Money } from './common';

export type DisputeReasonCode =
  | 'GOODS_NOT_RECEIVED'
  | 'SERVICE_NOT_RENDERED'
  | 'PRODUCT_NOT_AS_DESCRIBED'
  | 'DUPLICATE_PROCESSING'
  | 'CREDIT_NOT_PROCESSED'
  | 'SUBSCRIPTION_CANCELLED'
  | 'FRAUDULENT_TRANSACTION'
  | 'UNRECOGNIZED_TRANSACTION'
  | 'INCORRECT_AMOUNT'
  | 'PAID_BY_OTHER_MEANS';

export const DISPUTE_REASON_CODES: readonly DisputeReasonCode[] = [
  'GOODS_NOT_RECEIVED',
  'SERVICE_NOT_RENDERED',
  'PRODUCT_NOT_AS_DESCRIBED',
  'DUPLICATE_PROCESSING',
  'CREDIT_NOT_PROCESSED',
  'SUBSCRIPTION_CANCELLED',
  'FRAUDULENT_TRANSACTION',
  'UNRECOGNIZED_TRANSACTION',
  'INCORRECT_AMOUNT',
  'PAID_BY_OTHER_MEANS',
] as const;

export type DisputeStatus =
  | 'OPEN'
  | 'EVIDENCE_GATHERING'
  | 'UNDER_INVESTIGATION'
  | 'AWAITING_HUMAN_REVIEW'
  | 'REPRESENTMENT_PREPARED'
  | 'SUBMITTED'
  | 'WON'
  | 'LOST'
  | 'EXPIRED'
  | 'WITHDRAWN';

export const DISPUTE_STATUSES: readonly DisputeStatus[] = [
  'OPEN',
  'EVIDENCE_GATHERING',
  'UNDER_INVESTIGATION',
  'AWAITING_HUMAN_REVIEW',
  'REPRESENTMENT_PREPARED',
  'SUBMITTED',
  'WON',
  'LOST',
  'EXPIRED',
  'WITHDRAWN',
] as const;

/** Terminal statuses - mirrors `DisputeView.isTerminal()`. */
export const TERMINAL_DISPUTE_STATUSES: readonly DisputeStatus[] = [
  'WON',
  'LOST',
  'EXPIRED',
  'WITHDRAWN',
] as const;

export function isTerminalDispute(status: DisputeStatus): boolean {
  return TERMINAL_DISPUTE_STATUSES.includes(status);
}

/** `GET /disputes/{disputeId}` - mirrors `core.model.DisputeView`. */
export interface DisputeView {
  disputeId: string;
  merchantId: string;
  transactionId: string;
  reasonCode: DisputeReasonCode;
  status: DisputeStatus;
  /** Disputed amount. Minor units + currency, never a float. */
  amount: Money;
  networkCaseRef: string | null;
  source: string;
  openedAt: Iso8601;
  deadlineAt: Iso8601 | null;
  closedAt: Iso8601 | null;
  updatedAt: Iso8601;
}

/** Query shape of `GET /disputes`. */
export interface DisputeQuery {
  merchantId?: string;
  status?: DisputeStatus;
  reasonCode?: DisputeReasonCode;
  page?: number;
  size?: number;
}

/** Body of `POST /disputes` - manual or injected dispute creation. */
export interface CreateDisputeRequest {
  merchantId: string;
  transactionId: string;
  reasonCode: DisputeReasonCode;
  amount: Money;
  networkCaseRef?: string;
  source?: string;
  openedAt?: Iso8601;
  deadlineAt?: Iso8601;
}
