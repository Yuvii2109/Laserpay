/** `/audit*` - contract 8.1 (gateway view of the hash-chained audit log). */
import { api } from '@/lib/api/client';
import type { PageResponse } from '@/lib/types/common';
import type { AuditEventView, AuditQuery, ChainVerification } from '@/lib/types/audit';

export const auditApi = {
  /** `GET /audit?entityId=&entityType=&page=` */
  list(query: AuditQuery = {}, signal?: AbortSignal) {
    return api.get<PageResponse<AuditEventView>>('/audit', {
      query: {
        entityId: query.entityId,
        entityType: query.entityType,
        merchantId: query.merchantId,
        actor: query.actor,
        from: query.from,
        to: query.to,
        page: query.page,
        size: query.size,
      },
      signal,
    });
  },

  /** `GET /audit/verify-chain?merchantId=` - recompute the chain, report first divergence. */
  verifyChain(merchantId: string, signal?: AbortSignal) {
    return api.get<ChainVerification>('/audit/verify-chain', { query: { merchantId }, signal });
  },
} as const;
