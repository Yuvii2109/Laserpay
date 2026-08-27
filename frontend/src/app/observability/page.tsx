import type { Metadata } from 'next';
import { ObservabilityView } from './_components/ObservabilityView';

export const metadata: Metadata = {
  title: 'Observability',
  description: 'The events-to-human funnel, platform consoles and service health.',
};

/** Contract 14: `/observability` - funnel plus metrics summary. */
export default function ObservabilityPage() {
  return <ObservabilityView />;
}
