import type { Metadata } from 'next';
import { DisputesView } from './_components/DisputesView';
import {
  DISPUTE_REASON_CODES,
  DISPUTE_STATUSES,
  type DisputeReasonCode,
  type DisputeStatus,
} from '@/lib/types/dispute';

export const metadata: Metadata = {
  title: 'Disputes',
  description: 'Dispute list with status and reason-code filters, ordered by response deadline.',
};

function asString(value: string | string[] | undefined): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

/** `/disputes` (contract 14). Server shell so `?status=` / `?reasonCode=` deep links work. */
export default async function DisputesPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const status = asString(params['status']);
  const reasonCode = asString(params['reasonCode']);

  return (
    <DisputesView
      initialStatus={DISPUTE_STATUSES.find((item) => item === status) as DisputeStatus | undefined}
      initialReasonCode={
        DISPUTE_REASON_CODES.find((item) => item === reasonCode) as DisputeReasonCode | undefined
      }
    />
  );
}
