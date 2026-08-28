/**
 * Browser-visible configuration. Names are normative (contract §15) and must be read
 * as full `process.env.NEXT_PUBLIC_*` expressions so Next can inline them at build time.
 */

const DEFAULT_API_BASE_URL = 'http://localhost:8080/api/v1';
const DEFAULT_WS_URL = 'ws://localhost:8080/ws/control-tower';

function trimTrailingSlash(value: string): string {
  return value.endsWith('/') ? value.slice(0, -1) : value;
}

/** `http://host:8080/api/v1` -> `http://host:8088/sim/v1`. Falls back to localhost. */
function deriveSimBaseUrl(apiBaseUrl: string): string {
  try {
    const url = new URL(apiBaseUrl);
    return `${url.protocol}//${url.hostname}:8088/sim/v1`;
  } catch {
    return 'http://localhost:8088/sim/v1';
  }
}

export const config = {
  /** api-gateway-service REST base - contract §8.1. */
  apiBaseUrl: trimTrailingSlash(process.env.NEXT_PUBLIC_API_BASE_URL ?? DEFAULT_API_BASE_URL),
  /** Control-tower WebSocket - contract §8.1 streaming. */
  wsUrl: process.env.NEXT_PUBLIC_WS_URL ?? DEFAULT_WS_URL,
  /**
   * When true the api client and the socket serve deterministic fixtures from `src/mocks`
   * instead of touching the network, so the console is fully explorable with the backend down.
   */
  useMocks: (process.env.NEXT_PUBLIC_USE_MOCKS ?? 'false').toLowerCase() === 'true',
  /**
   * simulator-service base (contract 8.5, host port 8088). Derived from the gateway origin
   * unless NEXT_PUBLIC_SIM_BASE_URL is set, because the simulator is not behind the gateway.
   */
  simBaseUrl: process.env.NEXT_PUBLIC_SIM_BASE_URL ?? deriveSimBaseUrl(process.env.NEXT_PUBLIC_API_BASE_URL ?? DEFAULT_API_BASE_URL),
  appName: 'PDEI',
  appLongName: 'Pre-Dispute Evidence Intelligence',
} as const;

/** Server-Sent Events base, derived from the REST base (contract §8.1). */
export function streamUrl(path: string): string {
  return `${config.apiBaseUrl}/stream${path.startsWith('/') ? path : `/${path}`}`;
}
