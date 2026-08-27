import type { Metadata } from 'next';
import { EvidenceExplorerView } from './_components/EvidenceExplorerView';
import {
  EVIDENCE_STATUSES,
  EVIDENCE_TYPES,
  type EvidenceStatus,
  type EvidenceType,
} from '@/lib/types/evidence';

export const metadata: Metadata = {
  title: 'Evidence',
  description: 'Full-text evidence explorer with type and status facets.',
};

function asString(value: string | string[] | undefined): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

/**
 * `/evidence` (contract 14).
 *
 * Deep links (`?q=`, `?type=`, `?status=`) are read here rather than through `useSearchParams`,
 * which would need a Suspense boundary on this segment. Unknown enum members are dropped
 * instead of forwarded: the gateway would reject them anyway, and a silent 400 is worse than
 * an unfiltered list.
 */
export default async function EvidencePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const type = asString(params['type']);
  const status = asString(params['status']);

  return (
    <EvidenceExplorerView
      initialQuery={asString(params['q'])}
      initialType={EVIDENCE_TYPES.find((item) => item === type) as EvidenceType | undefined}
      initialStatus={EVIDENCE_STATUSES.find((item) => item === status) as EvidenceStatus | undefined}
    />
  );
}
