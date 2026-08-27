/**
 * Evidence domain types - contract 6 enums, `core.model.EvidenceView`,
 * `core.model.EvidenceGraph`, `core.spi.EvidenceVersionRecord`,
 * `core.evidence.IntegrityReport`.
 */
import type { Iso8601 } from './common';
import type { AggregateType } from './events';

export type EvidenceType =
  | 'PAYMENT_PROOF'
  | 'INVOICE'
  | 'ORDER_RECORD'
  | 'SHIPPING_RECORD'
  | 'DELIVERY_PROOF'
  | 'REFUND_RECEIPT'
  | 'CUSTOMER_COMMUNICATION'
  | 'MERCHANT_POLICY'
  | 'TERMS_OF_SERVICE'
  | 'AVS_CVV_RESULT'
  | 'DEVICE_FINGERPRINT'
  | 'PRIOR_TRANSACTION_HISTORY'
  | 'SIGNED_CONTRACT';

export const EVIDENCE_TYPES: readonly EvidenceType[] = [
  'PAYMENT_PROOF',
  'INVOICE',
  'ORDER_RECORD',
  'SHIPPING_RECORD',
  'DELIVERY_PROOF',
  'REFUND_RECEIPT',
  'CUSTOMER_COMMUNICATION',
  'MERCHANT_POLICY',
  'TERMS_OF_SERVICE',
  'AVS_CVV_RESULT',
  'DEVICE_FINGERPRINT',
  'PRIOR_TRANSACTION_HISTORY',
  'SIGNED_CONTRACT',
] as const;

export type EvidenceStatus =
  | 'PENDING'
  | 'ACTIVE'
  | 'EXPIRING'
  | 'EXPIRED'
  | 'INVALIDATED'
  | 'SUPERSEDED';

export const EVIDENCE_STATUSES: readonly EvidenceStatus[] = [
  'PENDING',
  'ACTIVE',
  'EXPIRING',
  'EXPIRED',
  'INVALIDATED',
  'SUPERSEDED',
] as const;

export type EvidenceSource =
  | 'PSP_ADAPTER'
  | 'ORDER_SYSTEM'
  | 'LOGISTICS'
  | 'CRM'
  | 'DOCUMENT_UPLOAD'
  | 'MERCHANT_PORTAL'
  | 'SIMULATOR'
  | 'INTERNAL_DERIVED';

export const EVIDENCE_SOURCES: readonly EvidenceSource[] = [
  'PSP_ADAPTER',
  'ORDER_SYSTEM',
  'LOGISTICS',
  'CRM',
  'DOCUMENT_UPLOAD',
  'MERCHANT_PORTAL',
  'SIMULATOR',
  'INTERNAL_DERIVED',
] as const;

/** Statuses that let an artifact satisfy a requirement - mirrors `EvidenceView.USABLE`. */
export const USABLE_EVIDENCE_STATUSES: readonly EvidenceStatus[] = ['ACTIVE', 'EXPIRING'] as const;

export function isUsableEvidence(status: EvidenceStatus): boolean {
  return USABLE_EVIDENCE_STATUSES.includes(status);
}

/** One evidence artifact - mirrors `core.model.EvidenceView` field for field. */
export interface EvidenceView {
  evidenceId: string;
  merchantId: string;
  transactionId: string;
  type: EvidenceType;
  status: EvidenceStatus;
  source: EvidenceSource;
  objectKey: string;
  sha256: string;
  version: number;
  filename: string;
  contentType: string;
  /** Byte length of the stored object. Not money; plain integer. */
  sizeBytes: number;
  summary: string | null;
  sourceEventId: string | null;
  parentEvidenceId: string | null;
  relatedEntityId: string | null;
  /** Extraction/quality score in [0,1]. */
  qualityScore: number;
  provenanceVerified: boolean;
  createdAt: Iso8601;
  observedAt: Iso8601;
  expiresAt: Iso8601 | null;
}

/** One immutable row of `pdei.evidence_versions` - mirrors `core.spi.EvidenceVersionRecord`. */
export interface EvidenceVersionRecord {
  evidenceVersionId: string;
  evidenceId: string;
  version: number;
  objectKey: string;
  sha256: string;
  sizeBytes: number;
  contentType: string;
  filename: string;
  sourceEventId: string | null;
  createdBy: string;
  createdAt: Iso8601;
}

/** One row of `pdei.evidence_relationships` - mirrors `core.spi.EvidenceRelationship`. */
export interface EvidenceRelationship {
  relationshipId: string;
  fromEvidenceId: string;
  toEvidenceId: string;
  relation: EvidenceRelation;
  detail: string | null;
  createdAt: Iso8601;
}

/** Edge relation constants - mirrors the constants on `core.model.EvidenceEdge`. */
export type EvidenceRelation =
  | 'HAS_PAYMENT'
  | 'HAS_ORDER'
  | 'HAS_REFUND'
  | 'HAS_COMMUNICATION'
  | 'SHIPPED_AS'
  | 'DELIVERED_AS'
  | 'REFUNDS'
  | 'EVIDENCES'
  | 'SUPERSEDES'
  | 'DERIVED_FROM'
  | 'CONTRADICTS'
  | 'RELATES_TO';

export const EVIDENCE_RELATIONS: readonly EvidenceRelation[] = [
  'HAS_PAYMENT',
  'HAS_ORDER',
  'HAS_REFUND',
  'HAS_COMMUNICATION',
  'SHIPPED_AS',
  'DELIVERED_AS',
  'REFUNDS',
  'EVIDENCES',
  'SUPERSEDES',
  'DERIVED_FROM',
  'CONTRADICTS',
  'RELATES_TO',
] as const;

/** Graph node - mirrors `core.model.EvidenceNode`. */
export interface EvidenceNode {
  id: string;
  type: AggregateType;
  label: string;
  /** Status string of the underlying entity (an `EvidenceStatus` for EVIDENCE nodes). */
  status: string | null;
  at: Iso8601 | null;
  attributes: Record<string, unknown>;
}

/** Graph edge - mirrors `core.model.EvidenceEdge`. */
export interface EvidenceEdge {
  from: string;
  to: string;
  relation: EvidenceRelation | string;
  attributes: Record<string, unknown>;
}

/** `GET /transactions/{id}/graph` - mirrors `core.model.EvidenceGraph`. */
export interface EvidenceGraph {
  rootId: string;
  nodes: EvidenceNode[];
  edges: EvidenceEdge[];
  generatedAt: Iso8601;
}

/** `GET /evidence/{id}/lineage` - version chain plus provenance walk. */
export interface EvidenceLineage {
  evidenceId: string;
  versions: EvidenceVersionRecord[];
  relationships: EvidenceRelationship[];
  /** Root of the version chain (the first version of this artifact). */
  rootEvidenceId: string;
  /** Ordered ancestor ids, oldest first. */
  ancestry: string[];
  generatedAt: Iso8601;
}

/** `POST /evidence/{id}/verify` - mirrors `core.evidence.IntegrityReport`. */
export interface IntegrityReport {
  evidenceId: string;
  objectKey: string;
  intact: boolean;
  objectMissing: boolean;
  expectedSha256: string;
  actualSha256: string | null;
  detail: string | null;
  verifiedAt: Iso8601;
}

/** Query shape of `GET /evidence` - mirrors `core.search.EvidenceSearchQuery`. */
export interface EvidenceSearchQuery {
  merchantId?: string;
  /** Free text, turned into a Postgres tsquery server-side. */
  q?: string;
  type?: EvidenceType;
  status?: EvidenceStatus;
  transactionId?: string;
  page?: number;
  size?: number;
}

/** Body of `POST /evidence` (multipart) - the merchant-portal upload. */
export interface EvidenceUploadRequest {
  merchantId: string;
  transactionId: string;
  type: EvidenceType;
  file: File;
  summary?: string;
  relatedEntityId?: string;
  /** Optional client-computed sha256; the gateway always recomputes. */
  sha256?: string;
}

/** `GET /evidence/{id}/download` resolves to a presigned MinIO URL. */
export interface EvidenceDownloadTicket {
  evidenceId: string;
  url: string;
  expiresAt: Iso8601;
}
