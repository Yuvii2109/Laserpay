/**
 * `/investigations/{investigationId}` - contract 8.1.
 * Read-only by design: the console never writes an AI result back (contract 17 rule 2).
 */
import { api } from '@/lib/api/client';
import type { InvestigationRecord } from '@/lib/types/ai';

export const investigationsApi = {
  /** `GET /investigations/{investigationId}` */
  get(investigationId: string, signal?: AbortSignal) {
    return api.get<InvestigationRecord>(
      `/investigations/${encodeURIComponent(investigationId)}`,
      { signal },
    );
  },
} as const;
