/** `/policies*` and `/requirements` - contract 8.1. Policy versions are immutable. */
import { api } from '@/lib/api/client';
import type { DisputeReasonCode } from '@/lib/types/dispute';
import type {
  PolicyDraft,
  PolicyQuery,
  PolicyView,
  RequirementsResponse,
} from '@/lib/types/policy';

const base = (policyId: string) => `/policies/${encodeURIComponent(policyId)}`;

export const policiesApi = {
  /**
   * `GET /policies?merchantId` - every policy of one merchant, newest version of each.
   * Not paged: `PolicyController.list` returns a bare `List<PolicyView>`, and `merchantId` is a
   * required request param (a call without it answers 400).
   */
  list(query: PolicyQuery, signal?: AbortSignal) {
    return api.get<PolicyView[]>('/policies', {
      query: { merchantId: query.merchantId },
      signal,
    });
  },

  /** `GET /policies/{policyId}` - the version currently in force. */
  get(policyId: string, signal?: AbortSignal) {
    return api.get<PolicyView>(base(policyId), { signal });
  },

  /** `GET /policies/{policyId}/requirements` - the matrix of that version, in its envelope. */
  requirements(policyId: string, signal?: AbortSignal) {
    return api.get<RequirementsResponse>(`${base(policyId)}/requirements`, { signal });
  },

  /**
   * `PUT /policies/{policyId}` - publishes a NEW immutable version and closes the previous
   * interval. It never edits history, so the response is a different `policyVersionId`.
   */
  update(policyId: string, draft: PolicyDraft) {
    return api.put<PolicyView>(base(policyId), { body: draft });
  },

  /**
   * `GET /requirements?reasonCode=GOODS_NOT_RECEIVED` - the requirement matrix for a reason code.
   * Merchant-scoped when `merchantId` is supplied, otherwise the seeded platform default;
   * `defaultPolicy` on the response says which of the two answered.
   */
  requirementsForReason(reasonCode: DisputeReasonCode, merchantId?: string, signal?: AbortSignal) {
    return api.get<RequirementsResponse>('/requirements', {
      query: { reasonCode, merchantId },
      signal,
    });
  },
} as const;
