/** `/metrics/funnel` - contract 8.1. */
import { api } from '@/lib/api/client';
import type { FunnelMetrics, FunnelQuery } from '@/lib/types/metrics';

export const metricsApi = {
  /** `GET /metrics/funnel` - events -> candidates -> ambiguous -> AI -> human. */
  funnel(query: FunnelQuery = {}, signal?: AbortSignal) {
    return api.get<FunnelMetrics>('/metrics/funnel', {
      query: { merchantId: query.merchantId, from: query.from, to: query.to },
      signal,
    });
  },
} as const;
