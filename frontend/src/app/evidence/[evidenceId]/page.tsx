import type { Metadata } from 'next';
import { EvidenceDetailView } from './_components/EvidenceDetailView';

interface RouteParams {
  params: Promise<{ evidenceId: string }>;
}

export async function generateMetadata({ params }: RouteParams): Promise<Metadata> {
  const { evidenceId } = await params;
  return {
    title: evidenceId,
    description: `Provenance, integrity, version history and lineage for ${evidenceId}.`,
  };
}

/** `/evidence/[evidenceId]` (contract 14). Server shell; the client view does the work. */
export default async function EvidenceDetailPage({ params }: RouteParams) {
  const { evidenceId } = await params;
  return <EvidenceDetailView evidenceId={evidenceId} />;
}
