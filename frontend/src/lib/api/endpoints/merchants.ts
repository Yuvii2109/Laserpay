/** `/merchants*` and `/health/ready` - contract 8.1. */
import { api } from '@/lib/api/client';
import type { Iso8601, PageResponse } from '@/lib/types/common';
import type { MerchantQuery, MerchantSummary, MerchantView } from '@/lib/types/merchant';

/** Reachability of one backing dependency, as the gateway probed it. */
export type DependencyStatus = 'UP' | 'DOWN' | 'UNKNOWN';

/**
 * `GET /health/ready` - mirrors `api.dto.HealthResponse`.
 *
 * `dependencies` is keyed by *infrastructure component* - `postgres`, `redis`, `kafka`,
 * `objectStore` (see `ReadinessProbeService.probe()`) - never by service name. The gateway
 * cannot speak for the other services: each actuator sits on its own origin.
 *
 * `status` is READY when every required dependency is up, DEGRADED when an optional one is down
 * (those names are listed in `degraded`), NOT_READY when Postgres is - the one dependency
 * without which no route works.
 */
export interface GatewayReadiness {
  status: 'READY' | 'DEGRADED' | 'NOT_READY';
  /** Always `api-gateway-service`. */
  service: string;
  dependencies: Record<string, DependencyStatus>;
  /** Dependency names that are down but not fatal. */
  degraded: string[];
  at: Iso8601;
}

export const merchantsApi = {
  /** `GET /merchants` */
  list(query: MerchantQuery = {}, signal?: AbortSignal) {
    return api.get<PageResponse<MerchantView>>('/merchants', {
      query: { page: query.page, size: query.size, q: query.q },
      signal,
    });
  },

  /** `GET /merchants/{merchantId}` */
  get(merchantId: string, signal?: AbortSignal) {
    return api.get<MerchantView>(`/merchants/${encodeURIComponent(merchantId)}`, { signal });
  },

  /** `GET /merchants/{merchantId}/summary` - the Control Tower KPI payload. */
  summary(merchantId: string, signal?: AbortSignal) {
    return api.get<MerchantSummary>(`/merchants/${encodeURIComponent(merchantId)}/summary`, {
      signal,
    });
  },

  /** `GET /health/ready` */
  ready(signal?: AbortSignal) {
    return api.get<GatewayReadiness>('/health/ready', { signal });
  },
} as const;
