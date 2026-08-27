'use client';

import { RouteErrorBoundary } from '@/components/shared/RouteErrorBoundary';

export default function TransactionDetailError({
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
      route="Transaction detail"
      backHref="/transactions"
      backLabel="Back to transactions"
    />
  );
}
