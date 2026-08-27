import type { Metadata } from 'next';
import { TransactionDetailView } from './_components/TransactionDetailView';

interface RouteParams {
  params: Promise<{ transactionId: string }>;
}

export async function generateMetadata({ params }: RouteParams): Promise<Metadata> {
  const { transactionId } = await params;
  return {
    title: transactionId,
    description: `Timeline, evidence, graph, gaps and readiness breakdown for ${transactionId}.`,
  };
}

/**
 * `/transactions/[transactionId]` (contract 14).
 *
 * A server shell that unwraps the route params, so the client view takes a plain string and
 * never has to deal with the params promise.
 */
export default async function TransactionDetailPage({ params }: RouteParams) {
  const { transactionId } = await params;
  return <TransactionDetailView transactionId={transactionId} />;
}
