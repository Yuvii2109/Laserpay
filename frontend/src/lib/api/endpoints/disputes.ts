/** `/disputes*` - contract 8.1. */
import { api } from '@/lib/api/client';
import type { PageResponse } from '@/lib/types/common';
import type { CreateDisputeRequest, DisputeQuery, DisputeView } from '@/lib/types/dispute';

export const disputesApi = {
  /** `GET /disputes?merchantId&status&reasonCode` */
  list(query: DisputeQuery = {}, signal?: AbortSignal) {
    return api.get<PageResponse<DisputeView>>('/disputes', {
      query: {
        merchantId: query.merchantId,
        status: query.status,
        reasonCode: query.reasonCode,
        page: query.page,
        size: query.size,
      },
      signal,
    });
  },

  /** `GET /disputes/{disputeId}` */
  get(disputeId: string, signal?: AbortSignal) {
    return api.get<DisputeView>(`/disputes/${encodeURIComponent(disputeId)}`, { signal });
  },

  /**
   * `POST /disputes` - manual or injected dispute creation.
   * `idempotencyKey` is honoured end to end; reuse it on retry so a double click cannot
   * create two disputes (contract 4: every consumer tolerates duplicates).
   */
  create(request: CreateDisputeRequest, idempotencyKey?: string) {
    return api.post<DisputeView>('/disputes', { body: request, idempotencyKey });
  },
} as const;
