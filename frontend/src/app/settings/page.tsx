import type { Metadata } from 'next';
import { SettingsView } from './_components/SettingsView';

export const metadata: Metadata = {
  title: 'Settings',
  description: 'Merchant scope, service endpoints, AI provider and console preferences.',
};

/** Contract 14: `/settings` - merchant and service configuration. */
export default function SettingsPage() {
  return <SettingsView />;
}
