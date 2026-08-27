import type { Metadata } from 'next';
import { PolicyConsole } from './_components/PolicyConsole';

export const metadata: Metadata = {
  title: 'Policies',
  description: 'Versioned requirement matrix and automation thresholds.',
};

interface PoliciesPageProps {
  /** Next 15 delivers search params asynchronously. `?policyId=` comes from `hrefForId()`. */
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

/** Contract 14: `/policies` - the reason-code by evidence-type matrix, versioned. */
export default async function PoliciesPage({ searchParams }: PoliciesPageProps) {
  const params = await searchParams;
  const raw = params['policyId'];
  const policyId = Array.isArray(raw) ? (raw[0] ?? null) : (raw ?? null);
  return <PolicyConsole initialPolicyId={policyId} />;
}
