'use client';

import { RouteErrorBoundary } from '@/components/shared/RouteErrorBoundary';

export default function DisputeDetailError({
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
      route="Dispute detail"
      backHref="/disputes"
      backLabel="Back to disputes"
    />
  );
}
