/** `/cases*` - contract 8.1. The three commands are Temporal signals (contract 10). */
import { api } from '@/lib/api/client';
import type { PageResponse } from '@/lib/types/common';
import type {
  CaseCommandResult,
  CaseDecisionRequest,
  CaseQuery,
  CaseSubmitRequest,
  CaseView,
  CaseXRay,
  PackageManifest,
} from '@/lib/types/case';

const base = (caseId: string) => `/cases/${encodeURIComponent(caseId)}`;

export const casesApi = {
  /** `GET /cases?status&merchantId` */
  list(query: CaseQuery = {}, signal?: AbortSignal) {
    return api.get<PageResponse<CaseView>>('/cases', {
      query: {
        merchantId: query.merchantId,
        status: query.status,
        page: query.page,
        size: query.size,
      },
      signal,
    });
  },

  /** `GET /cases/{caseId}` */
  get(caseId: string, signal?: AbortSignal) {
    return api.get<CaseView>(base(caseId), { signal });
  },

  /** `GET /cases/{caseId}/xray` - the full Case X-Ray payload behind every tab. */
  xray(caseId: string, signal?: AbortSignal) {
    return api.get<CaseXRay>(`${base(caseId)}/xray`, { signal });
  },

  /** `GET /cases/{caseId}/package` - representment package manifest. */
  packageManifest(caseId: string, signal?: AbortSignal) {
    return api.get<PackageManifest>(`${base(caseId)}/package`, { signal });
  },

  /** `POST /cases/{caseId}/approve` - human approval, signals `humanDecision`. */
  approve(caseId: string, request: CaseDecisionRequest, idempotencyKey?: string) {
    return api.post<CaseCommandResult>(`${base(caseId)}/approve`, {
      body: request,
      idempotencyKey,
    });
  },

  /** `POST /cases/{caseId}/reject` - human rejection, signals `humanDecision`. */
  reject(caseId: string, request: CaseDecisionRequest, idempotencyKey?: string) {
    return api.post<CaseCommandResult>(`${base(caseId)}/reject`, {
      body: request,
      idempotencyKey,
    });
  },

  /** `POST /cases/{caseId}/submit` - submit the prepared representment. */
  submit(caseId: string, request: CaseSubmitRequest, idempotencyKey?: string) {
    return api.post<CaseCommandResult>(`${base(caseId)}/submit`, {
      body: request,
      idempotencyKey,
    });
  },
} as const;
