/**
 * The single door to the backend. Every network call in this app goes through here
 * (contract 14: "All API access goes through frontend/src/lib/api/client.ts").
 *
 * Responsibilities:
 *   - base URL from NEXT_PUBLIC_API_BASE_URL (contract 15), default http://localhost:8080/api/v1
 *   - a correlation id on every request, echoed into thrown errors so a UI failure can be
 *     grepped straight out of Loki
 *   - normalisation of every failure into ApiError (types/common.ts)
 *   - query-string building that drops null/undefined and expands arrays
 *   - mock mode: when NEXT_PUBLIC_USE_MOCKS=true the request never leaves the browser and is
 *     answered from src/mocks, so the console is explorable with the whole backend down
 */
import { config } from '@/lib/config';
import type { ApiError as ApiErrorShape, ErrorResponseBody } from '@/lib/types/common';
import { isRecord } from '@/lib/utils';

export const CORRELATION_HEADER = 'X-Correlation-Id';
export const IDEMPOTENCY_HEADER = 'Idempotency-Key';

/** Values a query parameter may take. undefined/null params are dropped entirely. */
export type QueryValue = string | number | boolean | null | undefined | readonly (string | number)[];
export type QueryParams = Record<string, QueryValue>;

export interface RequestOptions {
  query?: QueryParams;
  /** Serialised as JSON unless `formData` is set. */
  body?: unknown;
  formData?: FormData;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** Sent as `Idempotency-Key`; required by ingestion-service, harmless elsewhere. */
  idempotencyKey?: string;
  /** Overrides the correlation id; one is generated per request otherwise. */
  correlationId?: string;
  /** Absolute base override - for services that are not the gateway (simulator, docproc). */
  baseUrl?: string;
  /** Do not follow the redirect of `/evidence/{id}/download`; surface the Location instead. */
  rawRedirect?: boolean;
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/** Normalised error thrown by every function in this module. Implements `ApiError`. */
export class ApiRequestError extends Error implements ApiErrorShape {
  override readonly name = 'ApiError' as const;
  readonly status: number;
  readonly code: string;
  readonly correlationId: string | null;
  readonly at: string;
  readonly details: Record<string, unknown>;
  readonly url: string;
  readonly method: string;

  constructor(init: {
    message: string;
    status: number;
    code: string;
    correlationId: string | null;
    at?: string;
    details?: Record<string, unknown>;
    url: string;
    method: string;
  }) {
    super(init.message);
    this.status = init.status;
    this.code = init.code;
    this.correlationId = init.correlationId;
    this.at = init.at ?? new Date().toISOString();
    this.details = init.details ?? {};
    this.url = init.url;
    this.method = init.method;
  }

  /** 5xx, 408, 429 and transport failures are worth retrying; other 4xx are not. */
  get retryable(): boolean {
    return this.status === 0 || this.status === 408 || this.status === 429 || this.status >= 500;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export function isApiError(error: unknown): error is ApiRequestError {
  return error instanceof ApiRequestError;
}

/** RFC4122-ish id. `crypto.randomUUID` when available, a plain fallback otherwise. */
export function newCorrelationId(): string {
  const cryptoRef = globalThis.crypto;
  if (cryptoRef && typeof cryptoRef.randomUUID === 'function') {
    return cryptoRef.randomUUID();
  }
  const rand = () =>
    Math.floor(Math.random() * 0x10000)
      .toString(16)
      .padStart(4, '0');
  return `${rand()}${rand()}-${rand()}-4${rand().slice(1)}-a${rand().slice(1)}-${rand()}${rand()}${rand()}`;
}

/**
 * Builds `?a=1&b=x&b=y`. Drops undefined/null/empty-string and expands arrays into repeated
 * keys. Returns '' when nothing survives, so callers can concatenate unconditionally.
 */
export function buildQueryString(params?: QueryParams): string {
  if (!params) return '';
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      for (const item of value) {
        if (item === undefined || item === null || item === '') continue;
        search.append(key, String(item));
      }
      continue;
    }
    search.append(key, String(value));
  }
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}

function resolveUrl(path: string, options?: RequestOptions): string {
  const base = options?.baseUrl ?? config.apiBaseUrl;
  const normalisedPath = path.startsWith('/') ? path : `/${path}`;
  return `${base}${normalisedPath}${buildQueryString(options?.query)}`;
}

function parseErrorBody(raw: string): Partial<ErrorResponseBody> | null {
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    return isRecord(parsed) ? (parsed as Partial<ErrorResponseBody>) : null;
  } catch {
    return null;
  }
}

async function toApiError(
  response: Response,
  url: string,
  method: string,
  correlationId: string,
): Promise<ApiRequestError> {
  const raw = await response.text().catch(() => '');
  const body = parseErrorBody(raw);
  return new ApiRequestError({
    status: response.status,
    code: body?.code ?? `HTTP_${response.status}`,
    message:
      body?.message ??
      (raw && raw.length < 400 ? raw : `${method} ${url} failed with ${response.status}`),
    correlationId: body?.correlationId ?? correlationId,
    at: body?.at,
    details: isRecord(body?.details) ? body.details : {},
    url,
    method,
  });
}

async function parseBody<T>(response: Response): Promise<T> {
  if (response.status === 204 || response.status === 205) {
    return undefined as T;
  }
  const text = await response.text();
  if (!text) return undefined as T;
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('json')) {
    return text as unknown as T;
  }
  return JSON.parse(text) as T;
}

/**
 * Core request. In mock mode the call is short-circuited into `src/mocks` before any network
 * access, which keeps every endpoint module below completely mock-unaware.
 */
export async function request<T>(
  method: HttpMethod,
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const correlationId = options.correlationId ?? newCorrelationId();
  const url = resolveUrl(path, options);

  if (config.useMocks) {
    const { resolveMockRequest } = await import('@/mocks');
    return resolveMockRequest<T>({
      method,
      path,
      query: options.query,
      body: options.body,
      url,
      correlationId,
    });
  }

  const headers: Record<string, string> = {
    Accept: 'application/json',
    [CORRELATION_HEADER]: correlationId,
    ...options.headers,
  };
  if (options.idempotencyKey) {
    headers[IDEMPOTENCY_HEADER] = options.idempotencyKey;
  }

  let payload: BodyInit | undefined;
  if (options.formData) {
    payload = options.formData;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(options.body);
  }

  let response: Response;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: payload,
      signal: options.signal,
      cache: 'no-store',
      redirect: options.rawRedirect ? 'manual' : 'follow',
      credentials: 'same-origin',
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') {
      throw cause;
    }
    throw new ApiRequestError({
      status: 0,
      code: 'NETWORK_UNAVAILABLE',
      message:
        cause instanceof Error
          ? `Cannot reach api-gateway-service at ${config.apiBaseUrl} (${cause.message})`
          : `Cannot reach api-gateway-service at ${config.apiBaseUrl}`,
      correlationId,
      url,
      method,
    });
  }

  if (!response.ok) {
    throw await toApiError(response, url, method, correlationId);
  }

  return parseBody<T>(response);
}

export const api = {
  get: <T,>(path: string, options?: RequestOptions) => request<T>('GET', path, options),
  post: <T,>(path: string, options?: RequestOptions) => request<T>('POST', path, options),
  put: <T,>(path: string, options?: RequestOptions) => request<T>('PUT', path, options),
  patch: <T,>(path: string, options?: RequestOptions) => request<T>('PATCH', path, options),
  delete: <T,>(path: string, options?: RequestOptions) => request<T>('DELETE', path, options),
} as const;
