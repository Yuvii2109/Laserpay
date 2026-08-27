import type { Metadata } from 'next';
import { SimulationConsole } from './_components/SimulationConsole';

export const metadata: Metadata = {
  title: 'Simulation',
  description: 'Seeded workload runs, curated scenarios and chaos injection.',
};

interface SimulationPageProps {
  /** `?runId=` comes from `hrefForId()` for SIM- ids. */
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

/** Contract 14: `/simulation` - the Simulation & Chaos Console. */
export default async function SimulationPage({ searchParams }: SimulationPageProps) {
  const params = await searchParams;
  const raw = params['runId'];
  const runId = Array.isArray(raw) ? (raw[0] ?? null) : (raw ?? null);
  return <SimulationConsole initialRunId={runId} />;
}
