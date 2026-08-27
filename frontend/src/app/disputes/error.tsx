'use client';

import { RouteErrorBoundary } from '@/components/shared/RouteErrorBoundary';

export default function DisputesError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return <RouteErrorBoundary error={error} reset={reset} route="Disputes" />;
}
