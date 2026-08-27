/** `/gaps` - contract 8.1, the at-risk feed behind the Control Tower. */
import { api } from '@/lib/api/client';
import type { PageResponse } from '@/lib/types/common';
import type { GapFeedItem, GapQuery } from '@/lib/types/readiness';

export const gapsApi = {
  /** `GET /gaps?merchantId&type&severity` */
  list(query: GapQuery = {}, signal?: AbortSignal) {
    return api.get<PageResponse<GapFeedItem>>('/gaps', {
      query: {
        merchantId: query.merchantId,
        type: query.type,
        severity: query.severity,
        page: query.page,
        size: query.size,
      },
      signal,
    });
  },
} as const;
