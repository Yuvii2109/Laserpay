'use client';

import { RouteErrorBoundary } from '@/components/shared/RouteErrorBoundary';

export default function EvidenceDetailError({
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
      route="Evidence detail"
      backHref="/evidence"
      backLabel="Back to the evidence explorer"
    />
  );
}
