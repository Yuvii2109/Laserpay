import type { NextConfig } from 'next';

/**
 * PDEI web (`pdei-web`) - see docs/PLATFORM-CONTRACT.md §2 and §14.
 *
 * `output: 'standalone'` produces the self-contained server bundle the Dockerfile copies.
 * Nothing here reads secrets: the only browser-visible configuration is the
 * NEXT_PUBLIC_* surface declared in .env.local.example.
 */
const nextConfig: NextConfig = {
  output: 'standalone',
  reactStrictMode: true,
  poweredByHeader: false,
  eslint: {
    // CI runs `npm run lint` explicitly; keep `next build` about compilation.
    ignoreDuringBuilds: true,
  },
  typescript: {
    ignoreBuildErrors: false,
  },
  experimental: {
    optimizePackageImports: ['lucide-react', 'date-fns', 'recharts'],
  },
};

export default nextConfig;
