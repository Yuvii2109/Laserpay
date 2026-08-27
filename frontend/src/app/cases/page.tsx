import type { Metadata } from 'next';
import { CaseQueueBoard } from './_components/CaseQueueBoard';

export const metadata: Metadata = {
  title: 'Cases',
  description: 'Dispute case queue in Temporal workflow swimlanes.',
};

/** Contract 14: `/cases` - the case queue as CaseStatus swimlanes. */
export default function CasesPage() {
  return <CaseQueueBoard />;
}
