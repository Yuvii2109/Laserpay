/** `/transactions*` - contract 8.1. */
import { api } from '@/lib/api/client';
import type { PageResponse } from '@/lib/types/common';
import type { TimelineEntry } from '@/lib/types/events';
import type { EvidenceGraph, EvidenceView } from '@/lib/types/evidence';
import type { ReadinessSnapshot } from '@/lib/types/readiness';
import type { DisputeReasonCode } from '@/lib/types/dispute';
import type { TransactionDetail, TransactionQuery, TransactionView } from '@/lib/types/transaction';

const base = (transactionId: string) => `/transactions/${encodeURIComponent(transactionId)}`;

export const transactionsApi = {
  /** `GET /transactions?merchantId&band&from&to&page&size` */
  list(query: TransactionQuery = {}, signal?: AbortSignal) {
    return api.get<PageResponse<TransactionView>>('/transactions', {
      query: {
        merchantId: query.merchantId,
        band: query.band,
        from: query.from,
        to: query.to,
        q: query.q,
        page: query.page,
        size: query.size,
      },
      signal,
    });
  },

  /** `GET /transactions/{transactionId}` */
  get(transactionId: string, signal?: AbortSignal) {
    return api.get<TransactionDetail>(base(transactionId), { signal });
  },

  /** `GET /transactions/{transactionId}/timeline` - unified event + evidence timeline. */
  timeline(transactionId: string, signal?: AbortSignal) {
    return api.get<TimelineEntry[]>(`${base(transactionId)}/timeline`, { signal });
  },

  /** `GET /transactions/{transactionId}/readiness` */
  readiness(transactionId: string, reasonCode?: DisputeReasonCode, signal?: AbortSignal) {
    return api.get<ReadinessSnapshot>(`${base(transactionId)}/readiness`, {
      query: { reasonCode },
      signal,
    });
  },

  /**
   * `POST /transactions/{transactionId}/readiness/recompute`
   * Deterministic recomputation (contract 7). Never an AI call.
   */
  recomputeReadiness(transactionId: string, reasonCode?: DisputeReasonCode) {
    return api.post<ReadinessSnapshot>(`${base(transactionId)}/readiness/recompute`, {
      query: { reasonCode },
    });
  },

  /** `GET /transactions/{transactionId}/evidence` */
  evidence(transactionId: string, signal?: AbortSignal) {
    return api.get<EvidenceView[]>(`${base(transactionId)}/evidence`, { signal });
  },

  /** `GET /transactions/{transactionId}/graph` - evidence graph (nodes + edges). */
  graph(transactionId: string, signal?: AbortSignal) {
    return api.get<EvidenceGraph>(`${base(transactionId)}/graph`, { signal });
  },
} as const;
