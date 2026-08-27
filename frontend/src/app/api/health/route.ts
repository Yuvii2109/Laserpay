import { NextResponse } from 'next/server';
import { config } from '@/lib/config';

/**
 * `GET /api/health` - the container health endpoint for `pdei-web` (contract 2).
 *
 * It reports the process, not the platform: a green answer means Next is serving. The gateway
 * it is configured against is reported so a misconfigured NEXT_PUBLIC_API_BASE_URL is visible
 * from the outside, but it is deliberately NOT probed - a health check that fails because a
 * dependency is down turns one outage into two.
 */
export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

export function GET() {
  return NextResponse.json(
    {
      status: 'UP',
      service: 'pdei-web',
      checkedAt: new Date().toISOString(),
      config: {
        apiBaseUrl: config.apiBaseUrl,
        wsUrl: config.wsUrl,
        useMocks: config.useMocks,
      },
    },
    { headers: { 'Cache-Control': 'no-store' } },
  );
}
