import { Suspense } from 'react';
import type { Metadata } from 'next';
import { LoadingState } from '@/components/shared/LoadingState';
import { CaseXRayView } from './_components/CaseXRayView';

interface CaseDetailPageProps {
  /** Next 15 delivers route params asynchronously. */
  params: Promise<{ caseId: string }>;
}

export async function generateMetadata({ params }: CaseDetailPageProps): Promise<Metadata> {
  const { caseId } = await params;
  return {
    title: `Case ${decodeURIComponent(caseId)}`,
    description: 'Case X-Ray: timeline, graph, evidence, AI reasoning, safety gate and package.',
  };
}

/**
 * Contract 14: `/cases/[caseId]` - the Case X-Ray.
 *
 * The view reads its active tab from the query string, so it must sit inside a Suspense
 * boundary: `useSearchParams` opts a client subtree out of static prerendering and Next
 * requires the boundary to be explicit.
 */
export default async function CaseDetailPage({ params }: CaseDetailPageProps) {
  const { caseId } = await params;
  return (
    <Suspense fallback={<LoadingState variant="panel" label="Loading case" />}>
      <CaseXRayView caseId={decodeURIComponent(caseId)} />
    </Suspense>
  );
}
