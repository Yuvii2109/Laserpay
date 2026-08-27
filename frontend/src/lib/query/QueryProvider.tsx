/**
 * TanStack Query v5 provider. One QueryClient per browser session, created inside state so
 * React 19 strict-mode double-invocation cannot leak a second client.
 *
 * Defaults are tuned for an event-driven backend: data is refreshed by WebSocket-driven
 * invalidation (see useInvalidateOnWsEvent), so polling is off and staleTime is generous.
 */
'use client';

import { useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider, type QueryClientConfig } from '@tanstack/react-query';
import { isApiError } from '@/lib/api/client';

/** Exported so tests and the mock harness can build an identically configured client. */
export const queryClientConfig: QueryClientConfig = {
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: false,
      refetchOnReconnect: true,
      retry: (failureCount, error) => {
        // A 4xx from the gateway is a contract problem, not a blip: never retry it.
        if (isApiError(error) && !error.retryable) return false;
        return failureCount < 2;
      },
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
    },
    mutations: {
      // Financial commands are never retried automatically; the operator re-confirms instead.
      retry: false,
    },
  },
};

export function createQueryClient(): QueryClient {
  return new QueryClient(queryClientConfig);
}

export function QueryProvider({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createQueryClient);
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
