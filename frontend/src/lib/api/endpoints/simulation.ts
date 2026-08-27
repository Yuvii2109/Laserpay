/**
 * simulator-service - contract 8.5, base `http://localhost:8088/sim/v1`.
 * The simulator sits beside the gateway rather than behind it, so every call here passes an
 * explicit `baseUrl` (config.simBaseUrl, overridable with NEXT_PUBLIC_SIM_BASE_URL).
 */
import { api } from '@/lib/api/client';
import { config } from '@/lib/config';
import type {
  ChaosInjection,
  ChaosRequest,
  ReplayRequest,
  ReplayResult,
  Scenario,
  SimulationRun,
  SimulationRunRequest,
} from '@/lib/types/simulation';

const sim = () => ({ baseUrl: config.simBaseUrl });

export const simulationApi = {
  /** `POST /sim/v1/runs` - deterministic given `seed` (contract 17 rule 11). */
  startRun(request: SimulationRunRequest, idempotencyKey?: string) {
    return api.post<SimulationRun>('/runs', { ...sim(), body: request, idempotencyKey });
  },

  /** `GET /sim/v1/runs` */
  listRuns(signal?: AbortSignal) {
    return api.get<SimulationRun[]>('/runs', { ...sim(), signal });
  },

  /** `GET /sim/v1/runs/{runId}` - progress + stats; poll this while a run is RUNNING. */
  getRun(runId: string, signal?: AbortSignal) {
    return api.get<SimulationRun>(`/runs/${encodeURIComponent(runId)}`, { ...sim(), signal });
  },

  /** `POST /sim/v1/runs/{runId}/stop` */
  stopRun(runId: string) {
    return api.post<SimulationRun>(`/runs/${encodeURIComponent(runId)}/stop`, sim());
  },

  /** `POST /sim/v1/chaos` - inject one failure (contract 6 ChaosType). */
  injectChaos(request: ChaosRequest, idempotencyKey?: string) {
    return api.post<ChaosInjection>('/chaos', { ...sim(), body: request, idempotencyKey });
  },

  /** `GET /sim/v1/chaos` - injection history. */
  listChaos(signal?: AbortSignal) {
    return api.get<ChaosInjection[]>('/chaos', { ...sim(), signal });
  },

  /** `POST /sim/v1/replay` - replay a topic from an offset or timestamp. */
  replay(request: ReplayRequest) {
    return api.post<ReplayResult>('/replay', { ...sim(), body: request });
  },

  /** `GET /sim/v1/scenarios` - curated demo scenarios. */
  listScenarios(signal?: AbortSignal) {
    return api.get<Scenario[]>('/scenarios', { ...sim(), signal });
  },

  /** `POST /sim/v1/scenarios/{key}/run` */
  runScenario(key: string) {
    return api.post<SimulationRun>(`/scenarios/${encodeURIComponent(key)}/run`, sim());
  },
} as const;
