/**
 * Query key factory. Every key is hierarchical and starts with `['pdei']`, so a WebSocket
 * frame can invalidate a whole resource (`queryKeys.transactions.all()`) or one entity
 * (`queryKeys.transactions.detail(id)`) without string-matching anywhere.
 *
 * Rule for page code: never hand-write an array key. If a page needs a new key, add it here.
 */
import type { AuditQuery } from '@/lib/types/audit';
import type { CaseQuery } from '@/lib/types/case';
import type { DisputeQuery } from '@/lib/types/dispute';
import type { DisputeReasonCode } from '@/lib/types/dispute';
import type { EvidenceSearchQuery } from '@/lib/types/evidence';
import type { FunnelQuery } from '@/lib/types/metrics';
import type { GapQuery } from '@/lib/types/readiness';
import type { MerchantQuery } from '@/lib/types/merchant';
import type { PolicyQuery } from '@/lib/types/policy';
import type { TransactionQuery } from '@/lib/types/transaction';

const ROOT = ['pdei'] as const;

export const queryKeys = {
  all: ROOT,

  health: {
    all: () => [...ROOT, 'health'] as const,
    ready: () => [...ROOT, 'health', 'ready'] as const,
  },

  merchants: {
    all: () => [...ROOT, 'merchants'] as const,
    list: (query: MerchantQuery = {}) => [...ROOT, 'merchants', 'list', query] as const,
    detail: (merchantId: string) => [...ROOT, 'merchants', 'detail', merchantId] as const,
    summary: (merchantId: string) => [...ROOT, 'merchants', 'summary', merchantId] as const,
  },

  transactions: {
    all: () => [...ROOT, 'transactions'] as const,
    list: (query: TransactionQuery = {}) => [...ROOT, 'transactions', 'list', query] as const,
    detail: (transactionId: string) => [...ROOT, 'transactions', 'detail', transactionId] as const,
    timeline: (transactionId: string) =>
      [...ROOT, 'transactions', 'timeline', transactionId] as const,
    readiness: (transactionId: string, reasonCode?: DisputeReasonCode) =>
      [...ROOT, 'transactions', 'readiness', transactionId, reasonCode ?? null] as const,
    evidence: (transactionId: string) =>
      [...ROOT, 'transactions', 'evidence', transactionId] as const,
    graph: (transactionId: string) => [...ROOT, 'transactions', 'graph', transactionId] as const,
  },

  evidence: {
    all: () => [...ROOT, 'evidence'] as const,
    list: (query: EvidenceSearchQuery = {}) => [...ROOT, 'evidence', 'list', query] as const,
    detail: (evidenceId: string) => [...ROOT, 'evidence', 'detail', evidenceId] as const,
    versions: (evidenceId: string) => [...ROOT, 'evidence', 'versions', evidenceId] as const,
    lineage: (evidenceId: string) => [...ROOT, 'evidence', 'lineage', evidenceId] as const,
    integrity: (evidenceId: string) => [...ROOT, 'evidence', 'integrity', evidenceId] as const,
  },

  disputes: {
    all: () => [...ROOT, 'disputes'] as const,
    list: (query: DisputeQuery = {}) => [...ROOT, 'disputes', 'list', query] as const,
    detail: (disputeId: string) => [...ROOT, 'disputes', 'detail', disputeId] as const,
  },

  cases: {
    all: () => [...ROOT, 'cases'] as const,
    list: (query: CaseQuery = {}) => [...ROOT, 'cases', 'list', query] as const,
    detail: (caseId: string) => [...ROOT, 'cases', 'detail', caseId] as const,
    xray: (caseId: string) => [...ROOT, 'cases', 'xray', caseId] as const,
    packageManifest: (caseId: string) => [...ROOT, 'cases', 'package', caseId] as const,
  },

  investigations: {
    all: () => [...ROOT, 'investigations'] as const,
    detail: (investigationId: string) =>
      [...ROOT, 'investigations', 'detail', investigationId] as const,
  },

  policies: {
    all: () => [...ROOT, 'policies'] as const,
    list: (query: Partial<PolicyQuery> = {}) => [...ROOT, 'policies', 'list', query] as const,
    detail: (policyId: string) => [...ROOT, 'policies', 'detail', policyId] as const,
    requirements: (policyId: string) => [...ROOT, 'policies', 'requirements', policyId] as const,
    requirementsForReason: (reasonCode: DisputeReasonCode, merchantId?: string) =>
      [...ROOT, 'policies', 'requirements-for-reason', reasonCode, merchantId ?? null] as const,
  },

  gaps: {
    all: () => [...ROOT, 'gaps'] as const,
    list: (query: GapQuery = {}) => [...ROOT, 'gaps', 'list', query] as const,
  },

  audit: {
    all: () => [...ROOT, 'audit'] as const,
    list: (query: AuditQuery = {}) => [...ROOT, 'audit', 'list', query] as const,
    chain: (merchantId: string) => [...ROOT, 'audit', 'chain', merchantId] as const,
  },

  metrics: {
    all: () => [...ROOT, 'metrics'] as const,
    funnel: (query: FunnelQuery = {}) => [...ROOT, 'metrics', 'funnel', query] as const,
  },

  simulation: {
    all: () => [...ROOT, 'simulation'] as const,
    runs: () => [...ROOT, 'simulation', 'runs'] as const,
    run: (runId: string) => [...ROOT, 'simulation', 'run', runId] as const,
    chaos: () => [...ROOT, 'simulation', 'chaos'] as const,
    scenarios: () => [...ROOT, 'simulation', 'scenarios'] as const,
  },
} as const;

export type QueryKeys = typeof queryKeys;

/** Every resource root, for a hard "refresh everything" action. */
export function allResourceKeys(): readonly (readonly unknown[])[] {
  return [
    queryKeys.merchants.all(),
    queryKeys.transactions.all(),
    queryKeys.evidence.all(),
    queryKeys.disputes.all(),
    queryKeys.cases.all(),
    queryKeys.investigations.all(),
    queryKeys.policies.all(),
    queryKeys.gaps.all(),
    queryKeys.audit.all(),
    queryKeys.metrics.all(),
    queryKeys.simulation.all(),
  ];
}
