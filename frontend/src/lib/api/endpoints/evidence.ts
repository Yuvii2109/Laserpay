/** `/evidence*` - contract 8.1. */
import { api, buildQueryString } from '@/lib/api/client';
import { config } from '@/lib/config';
import type { PageResponse } from '@/lib/types/common';
import type {
  EvidenceLineage,
  EvidenceSearchQuery,
  EvidenceUploadRequest,
  EvidenceVersionRecord,
  EvidenceView,
  IntegrityReport,
} from '@/lib/types/evidence';

const base = (evidenceId: string) => `/evidence/${encodeURIComponent(evidenceId)}`;

export const evidenceApi = {
  /** `GET /evidence?merchantId&type&status&q` - Postgres FTS behind `q`. */
  search(query: EvidenceSearchQuery = {}, signal?: AbortSignal) {
    return api.get<PageResponse<EvidenceView>>('/evidence', {
      query: {
        merchantId: query.merchantId,
        q: query.q,
        type: query.type,
        status: query.status,
        transactionId: query.transactionId,
        page: query.page,
        size: query.size,
      },
      signal,
    });
  },

  /** `GET /evidence/{evidenceId}` */
  get(evidenceId: string, signal?: AbortSignal) {
    return api.get<EvidenceView>(base(evidenceId), { signal });
  },

  /** `GET /evidence/{evidenceId}/versions` - append-only version ledger. */
  versions(evidenceId: string, signal?: AbortSignal) {
    return api.get<EvidenceVersionRecord[]>(`${base(evidenceId)}/versions`, { signal });
  },

  /** `GET /evidence/{evidenceId}/lineage` - version chain + provenance walk. */
  lineage(evidenceId: string, signal?: AbortSignal) {
    return api.get<EvidenceLineage>(`${base(evidenceId)}/lineage`, { signal });
  },

  /**
   * `GET /evidence/{evidenceId}/download` responds 302 to a presigned MinIO URL.
   * Redirect-following cross-origin fetches cannot expose the Location header, so the browser
   * follows it: hand this string to an anchor/`window.open` rather than fetching it.
   */
  downloadUrl(evidenceId: string): string {
    return `${config.apiBaseUrl}${base(evidenceId)}/download${buildQueryString()}`;
  },

  /** `POST /evidence` - merchant-portal multipart upload. */
  upload(request: EvidenceUploadRequest) {
    const formData = new FormData();
    formData.append('merchantId', request.merchantId);
    formData.append('transactionId', request.transactionId);
    formData.append('type', request.type);
    formData.append('file', request.file, request.file.name);
    if (request.summary) formData.append('summary', request.summary);
    if (request.relatedEntityId) formData.append('relatedEntityId', request.relatedEntityId);
    if (request.sha256) formData.append('sha256', request.sha256);
    return api.post<EvidenceView>('/evidence', { formData });
  },

  /** `POST /evidence/{evidenceId}/verify` - re-hash the stored object and compare. */
  verify(evidenceId: string) {
    return api.post<IntegrityReport>(`${base(evidenceId)}/verify`);
  },
} as const;
