/**
 * Canonical event envelope - contract 3, mirrors `common.event.CanonicalEvent`.
 * Field names and enum members are exact; do not add variant spellings.
 */
import type { Iso8601 } from './common';

export type AggregateType =
  | 'MERCHANT'
  | 'CUSTOMER'
  | 'TRANSACTION'
  | 'PAYMENT'
  | 'ORDER'
  | 'SHIPMENT'
  | 'DELIVERY'
  | 'REFUND'
  | 'COMMUNICATION'
  | 'EVIDENCE'
  | 'POLICY'
  | 'DISPUTE'
  | 'CASE';

export const AGGREGATE_TYPES: readonly AggregateType[] = [
  'MERCHANT',
  'CUSTOMER',
  'TRANSACTION',
  'PAYMENT',
  'ORDER',
  'SHIPMENT',
  'DELIVERY',
  'REFUND',
  'COMMUNICATION',
  'EVIDENCE',
  'POLICY',
  'DISPUTE',
  'CASE',
] as const;

/** Provenance of a canonical event - contract 3 `source` enum. */
export type EventSource =
  | 'PSP_ADAPTER'
  | 'ORDER_SYSTEM'
  | 'LOGISTICS'
  | 'CRM'
  | 'SIMULATOR'
  | 'INTERNAL'
  | 'MERCHANT_PORTAL';

export const EVENT_SOURCES: readonly EventSource[] = [
  'PSP_ADAPTER',
  'ORDER_SYSTEM',
  'LOGISTICS',
  'CRM',
  'SIMULATOR',
  'INTERNAL',
  'MERCHANT_PORTAL',
] as const;

/** Canonical event types - contract 3.1. PascalCase on the wire, exactly as listed. */
export type EventType =
  | 'PaymentCreated'
  | 'PaymentAuthorized'
  | 'PaymentCaptured'
  | 'PaymentFailed'
  | 'OrderCreated'
  | 'OrderFulfilled'
  | 'OrderCancelled'
  | 'ShipmentCreated'
  | 'ShipmentDispatched'
  | 'ShipmentDelivered'
  | 'RefundCreated'
  | 'RefundProcessed'
  | 'CommunicationCreated'
  | 'CommunicationReceived'
  | 'EvidenceAdded'
  | 'EvidenceExpired'
  | 'EvidenceInvalidated'
  | 'DisputeCreated'
  | 'DisputeUpdated'
  | 'DisputeClosed'
  | 'ReadinessRecomputed'
  | 'ReadinessGapDetected'
  | 'CaseOpened'
  | 'CaseEvidenceAttached'
  | 'CaseInvestigated'
  | 'CasePrepared'
  | 'CaseEscalated'
  | 'CaseSubmitted'
  | 'CaseClosed'
  | 'AuditRecorded';

export const EVENT_TYPES: readonly EventType[] = [
  'PaymentCreated',
  'PaymentAuthorized',
  'PaymentCaptured',
  'PaymentFailed',
  'OrderCreated',
  'OrderFulfilled',
  'OrderCancelled',
  'ShipmentCreated',
  'ShipmentDispatched',
  'ShipmentDelivered',
  'RefundCreated',
  'RefundProcessed',
  'CommunicationCreated',
  'CommunicationReceived',
  'EvidenceAdded',
  'EvidenceExpired',
  'EvidenceInvalidated',
  'DisputeCreated',
  'DisputeUpdated',
  'DisputeClosed',
  'ReadinessRecomputed',
  'ReadinessGapDetected',
  'CaseOpened',
  'CaseEvidenceAttached',
  'CaseInvestigated',
  'CasePrepared',
  'CaseEscalated',
  'CaseSubmitted',
  'CaseClosed',
  'AuditRecorded',
] as const;

/**
 * `EventType -> AggregateType`, the TS mirror of `EventType.aggregateType()`.
 * READINESS events are carried on the owning TRANSACTION aggregate; AUDIT events
 * carry the aggregate of whatever they describe and are typed CASE by default.
 */
export const EVENT_TYPE_AGGREGATE: Readonly<Record<EventType, AggregateType>> = {
  PaymentCreated: 'PAYMENT',
  PaymentAuthorized: 'PAYMENT',
  PaymentCaptured: 'PAYMENT',
  PaymentFailed: 'PAYMENT',
  OrderCreated: 'ORDER',
  OrderFulfilled: 'ORDER',
  OrderCancelled: 'ORDER',
  ShipmentCreated: 'SHIPMENT',
  ShipmentDispatched: 'SHIPMENT',
  ShipmentDelivered: 'SHIPMENT',
  RefundCreated: 'REFUND',
  RefundProcessed: 'REFUND',
  CommunicationCreated: 'COMMUNICATION',
  CommunicationReceived: 'COMMUNICATION',
  EvidenceAdded: 'EVIDENCE',
  EvidenceExpired: 'EVIDENCE',
  EvidenceInvalidated: 'EVIDENCE',
  DisputeCreated: 'DISPUTE',
  DisputeUpdated: 'DISPUTE',
  DisputeClosed: 'DISPUTE',
  ReadinessRecomputed: 'TRANSACTION',
  ReadinessGapDetected: 'TRANSACTION',
  CaseOpened: 'CASE',
  CaseEvidenceAttached: 'CASE',
  CaseInvestigated: 'CASE',
  CasePrepared: 'CASE',
  CaseEscalated: 'CASE',
  CaseSubmitted: 'CASE',
  CaseClosed: 'CASE',
  AuditRecorded: 'CASE',
} as const;

/** Canonical event envelope - contract 3. `payload` is source-shaped and stays opaque here. */
export interface CanonicalEvent {
  eventId: string;
  eventType: EventType;
  schemaVersion: number;
  aggregateType: AggregateType;
  aggregateId: string;
  merchantId: string;
  correlationId: string;
  causationId: string | null;
  occurredAt: Iso8601;
  observedAt: Iso8601;
  source: EventSource;
  idempotencyKey: string;
  payload: Record<string, unknown>;
}

/** Unified event + evidence timeline row - mirrors `core.model.TimelineEntry`. */
export interface TimelineEntry {
  entryId: string;
  at: Iso8601;
  /** An `EventType` for canonical rows; a free-form label for derived rows. */
  eventType: string;
  aggregateType: AggregateType;
  aggregateId: string;
  summary: string;
  source: string;
  details: Record<string, unknown>;
}

/** Partition key used by every producer - contract 4. Useful on debug surfaces. */
export function partitionKey(event: Pick<CanonicalEvent, 'merchantId' | 'aggregateId'>): string {
  return `${event.merchantId}:${event.aggregateId}`;
}

export function isEvidenceEvent(type: EventType): boolean {
  return EVENT_TYPE_AGGREGATE[type] === 'EVIDENCE';
}

export function isDisputeEvent(type: EventType): boolean {
  return EVENT_TYPE_AGGREGATE[type] === 'DISPUTE';
}

export function isCaseEvent(type: EventType): boolean {
  return type.startsWith('Case');
}

export function isReadinessEvent(type: EventType): boolean {
  return type === 'ReadinessRecomputed' || type === 'ReadinessGapDetected';
}
