import type { Metadata } from 'next';
import { DisputeDetailView } from './_components/DisputeDetailView';

interface RouteParams {
  params: Promise<{ disputeId: string }>;
}

export async function generateMetadata({ params }: RouteParams): Promise<Metadata> {
  const { disputeId } = await params;
  return {
    title: disputeId,
    description: `Deadline, linked transaction and case, and the required-evidence checklist for ${disputeId}.`,
  };
}

/** `/disputes/[disputeId]` (contract 14). Server shell; the client view does the work. */
export default async function DisputeDetailPage({ params }: RouteParams) {
  const { disputeId } = await params;
  return <DisputeDetailView disputeId={disputeId} />;
}
