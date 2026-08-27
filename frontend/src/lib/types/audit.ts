/**
 * Audit types - contract 8.1 `/audit*` and 8.4 `audit-service`.
 * Mirrors `common.event.AuditEvent` / `AuditEventEntity` and `core.audit.ChainVerification`.
 */
import type { ActorType, Iso8601 } from './common';
import type { AggregateType } from './events';

/** One hash-chained audit entry. `hash` covers every field except itself. */
export interface AuditEventView {
  auditId: string;
  /** Monotonic per-merchant sequence used to walk the chain. */
  sequenceNo: number;
  entityType: AggregateType;
  entityId: string;
  merchantId: string;
  action: string;
  actor: string;
  actorType: ActorType;
  occurredAt: Iso8601;
  correlationId: string | null;
  causationId: string | null;
  sourceEventId: string | null;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  previousHash: string | null;
  hash: string;
}

/** Query shape of `GET /audit` and `GET /audit/v1/events`. */
export interface AuditQuery {
  entityId?: string;
  entityType?: AggregateType;
  merchantId?: string;
  actor?: string;
  from?: Iso8601;
  to?: Iso8601;
  page?: number;
  size?: number;
}

/** `GET /audit/verify-chain` - mirrors `core.audit.ChainVerification`. */
export interface ChainVerification {
  merchantId: string;
  intact: boolean;
  eventsChecked: number;
  firstDivergenceId: string | null;
  detail: string | null;
  verifiedAt: Iso8601;
}
