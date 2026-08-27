/**
 * Transaction types - contract 8.1 `/transactions*`.
 * `TransactionView` mirrors the `TransactionEntity` projection the gateway returns;
 * `TransactionFacts` mirrors `core.model.TransactionFacts` (the entity graph behind a transaction).
 */
import type { Iso8601, Money } from './common';
import type { EvidenceView } from './evidence';
import type { ReadinessBand, ReadinessSnapshot } from './readiness';

/** `GET /transactions` row and `GET /transactions/{id}` header. */
export interface TransactionView {
  transactionId: string;
  merchantId: string;
  customerId: string | null;
  externalRef: string | null;
  amount: Money;
  capturedAmount: Money | null;
  refundedAmount: Money | null;
  status: string;
  channel: string | null;
  occurredAt: Iso8601;
  observedAt: Iso8601;
  /** Latest readiness score 0-100; null until the readiness worker has run. */
  readinessScore: number | null;
  readinessBand: ReadinessBand | null;
  readinessComputedAt: Iso8601 | null;
  lastEventId: string | null;
  lastEventAt: Iso8601 | null;
  /** Present when a dispute exists for this transaction. */
  disputeId?: string | null;
  evidenceCount?: number;
  openGapCount?: number;
  metadata: Record<string, unknown>;
}

export interface PaymentFact {
  paymentId: string;
  status: string;
  amount: Money;
  processorReference: string | null;
  createdAt: Iso8601;
  authorizedAt: Iso8601 | null;
  capturedAt: Iso8601 | null;
  avsResult: string | null;
  cvvResult: string | null;
}

export interface OrderLineFact {
  lineId: string;
  sku: string;
  description: string;
  quantity: number;
  unitPrice: Money;
}

export interface OrderFact {
  orderId: string;
  status: string;
  total: Money;
  shippingAddress: string | null;
  createdAt: Iso8601;
  fulfilledAt: Iso8601 | null;
  lines: OrderLineFact[];
}

export interface ShipmentFact {
  shipmentId: string;
  orderId: string;
  carrier: string;
  trackingNumber: string;
  status: string;
  destinationAddress: string | null;
  quantity: number;
  createdAt: Iso8601;
  dispatchedAt: Iso8601 | null;
}

export interface DeliveryFact {
  deliveryId: string;
  shipmentId: string;
  status: string;
  signedBy: string | null;
  deliveredToAddress: string | null;
  proofType: string | null;
  deliveredAt: Iso8601 | null;
}

export interface RefundFact {
  refundId: string;
  paymentId: string;
  status: string;
  amount: Money;
  createdAt: Iso8601;
  processedAt: Iso8601 | null;
}

export interface CommunicationFact {
  communicationId: string;
  channel: string;
  direction: string;
  subject: string | null;
  body: string | null;
  occurredAt: Iso8601;
}

/** The entity graph behind a transaction - mirrors `core.model.TransactionFacts`. */
export interface TransactionFacts {
  transactionId: string;
  merchantId: string;
  customerId: string | null;
  amount: Money | null;
  status: string | null;
  createdAt: Iso8601 | null;
  payments: PaymentFact[];
  orders: OrderFact[];
  shipments: ShipmentFact[];
  deliveries: DeliveryFact[];
  refunds: RefundFact[];
  communications: CommunicationFact[];
}

/**
 * `GET /transactions/{id}` - mirrors `api.dto.TransactionDetailResponse`.
 *
 * The row is NESTED under `transaction`; it is not spread across the top level. `facts` is the
 * evidence-core projection reused verbatim, so this page and the contradiction detector are
 * looking at the identical shape. The heavy panels (timeline, graph, full evidence list) stay on
 * their own routes - `evidence` here is the summary list the page shows above the fold.
 */
export interface TransactionDetail {
  transaction: TransactionView;
  facts: TransactionFacts;
  /** Null until readiness-worker has scored this transaction. */
  readiness: ReadinessSnapshot | null;
  evidence: EvidenceView[];
  evidenceCount: number;
  openGapCount: number;
}

/** Query shape of `GET /transactions`. */
export interface TransactionQuery {
  merchantId?: string;
  band?: ReadinessBand;
  from?: Iso8601;
  to?: Iso8601;
  q?: string;
  page?: number;
  size?: number;
}
