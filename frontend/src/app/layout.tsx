import type { Metadata, Viewport } from 'next';
import './globals.css';
import { Providers } from './providers';
import { AppShell } from '@/components/shared/AppShell';
import { THEME_BOOTSTRAP_SCRIPT } from '@/components/shared/ThemeProvider';
import { config } from '@/lib/config';

export const metadata: Metadata = {
  title: {
    default: `${config.appName} - ${config.appLongName}`,
    template: `%s · ${config.appName}`,
  },
  description:
    'Operator console for the PDEI platform: evidence readiness, gap detection, dispute cases and AI-assisted investigation under deterministic policy control.',
  applicationName: config.appName,
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#f9f9f7' },
    { media: '(prefers-color-scheme: dark)', color: '#0d0d0d' },
  ],
};

/**
 * Root layout. Every route of contract 14 renders inside the same shell:
 * sidebar navigation, top bar with the merchant selector and the live connection state.
 *
 * `suppressHydrationWarning` on <html> is required because the pre-paint script below stamps
 * the theme onto the element before React hydrates.
 */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOTSTRAP_SCRIPT }} />
      </head>
      <body className="min-h-dvh bg-background text-foreground antialiased">
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
