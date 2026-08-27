'use client';

import type { ReactNode } from 'react';
import { QueryProvider } from '@/lib/query/QueryProvider';
import { TooltipProvider } from '@/components/ui/tooltip';
import { Toaster } from '@/components/ui/sonner';
import { ThemeProvider } from '@/components/shared/ThemeProvider';

/**
 * Client providers, mounted once by the root layout.
 *
 * Order matters: theme first (it only touches <html>), then server state, then the UI
 * primitives that depend on both.
 */
export function Providers({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider>
      <QueryProvider>
        <TooltipProvider delayDuration={200} skipDelayDuration={300}>
          {children}
          <Toaster />
        </TooltipProvider>
      </QueryProvider>
    </ThemeProvider>
  );
}
