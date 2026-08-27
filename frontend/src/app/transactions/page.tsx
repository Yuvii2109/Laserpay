import type { Metadata } from 'next';
import { TransactionsView } from './_components/TransactionsView';
import { READINESS_BANDS, type ReadinessBand } from '@/lib/types/readiness';

export const metadata: Metadata = {
  title: 'Transactions',
  description: 'Every transaction with its evidence readiness band, filters and server paging.',
};

function asBand(value: string | string[] | undefined): ReadinessBand | undefined {
  if (typeof value !== 'string') return undefined;
  return READINESS_BANDS.find((band) => band === value);
}

/**
 * `/transactions` (contract 14).
 *
 * A server shell so the deep-linked `?band=` can be read without `useSearchParams`, which would
 * force a Suspense boundary on an otherwise static segment. Everything interactive lives in the
 * client view.
 */
export default async function TransactionsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  return <TransactionsView initialBand={asBand(params['band'])} />;
}
