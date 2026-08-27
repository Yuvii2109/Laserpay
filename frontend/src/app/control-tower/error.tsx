'use client';

import { RouteErrorBoundary } from '@/components/shared/RouteErrorBoundary';

export default function ControlTowerError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <RouteErrorBoundary
      error={error}
      reset={reset}
      route="Control Tower"
      backHref="/transactions"
      backLabel="Go to transactions"
    />
  );
}
