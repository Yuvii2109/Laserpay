'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import {
  Bot,
  Building2,
  Check,
  Copy,
  ExternalLink,
  FlaskConical,
  Monitor,
  Moon,
  Plug,
  SlidersHorizontal,
  Sun,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import { toast } from '@/components/ui/sonner';
import { ErrorState } from '@/components/shared/ErrorState';
import { LoadingState } from '@/components/shared/LoadingState';
import { PageHeader } from '@/components/shared/PageHeader';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { merchantsApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { config, streamUrl } from '@/lib/config';
import { formatBps } from '@/lib/format/money';
import { UI_STORAGE_KEY, useUiStore, type TableDensity, type ThemePreference } from '@/lib/store/uiStore';
import { useLiveStore } from '@/lib/store/liveStore';
import { CONNECTION_LABEL } from '@/lib/types/ws';

const PAGE_SIZES = [10, 25, 50, 100] as const;

const THEME_OPTIONS: readonly { value: ThemePreference; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'system', label: 'System', icon: Monitor },
];

const DENSITY_OPTIONS: readonly { value: TableDensity; label: string }[] = [
  { value: 'comfortable', label: 'Comfortable' },
  { value: 'compact', label: 'Compact' },
];

/**
 * Settings.
 *
 * Two kinds of thing live here and they are deliberately not mixed. Merchant scope, theme,
 * density and time zone are *this browser's* preferences and are editable. Endpoints, the AI
 * provider and mock mode are *the deployment's* configuration: they are inlined at build time
 * from `NEXT_PUBLIC_*` and cannot be changed from a running page, so they are shown as facts
 * with the variable that controls them - never as a control that silently does nothing.
 */
export function SettingsView() {
  const merchantId = useUiStore((state) => state.selectedMerchantId);
  const setMerchantId = useUiStore((state) => state.setSelectedMerchantId);
  const theme = useUiStore((state) => state.theme);
  const setTheme = useUiStore((state) => state.setTheme);
  const density = useUiStore((state) => state.density);
  const setDensity = useUiStore((state) => state.setDensity);
  const timeZoneMode = useUiStore((state) => state.timeZoneMode);
  const setTimeZoneMode = useUiStore((state) => state.setTimeZoneMode);
  const pageSize = useUiStore((state) => state.pageSize);
  const setPageSize = useUiStore((state) => state.setPageSize);
  const resetFilters = useUiStore((state) => state.resetFilters);

  const socketStatus = useLiveStore((state) => state.status);

  const merchantsQuery = useQuery({
    queryKey: queryKeys.merchants.list({ size: 100 }),
    queryFn: ({ signal }) => merchantsApi.list({ size: 100 }, signal),
    staleTime: 5 * 60_000,
  });

  const merchantQuery = useQuery({
    queryKey: queryKeys.merchants.detail(merchantId ?? 'none'),
    queryFn: ({ signal }) => merchantsApi.get(merchantId as string, signal),
    enabled: Boolean(merchantId),
  });

  const merchants = merchantsQuery.data?.content ?? [];
  const merchant = merchantQuery.data;

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Verify"
        title="Settings"
        description="What this browser is looking at, and what this deployment is wired to. Deployment values are build-time constants; they are reported here, not edited."
        meta={
          <Badge variant={config.useMocks ? 'primary' : 'subtle'}>
            {config.useMocks ? 'Mock data' : 'Live backend'}
          </Badge>
        }
      />

      {/* ---------------------------------------------------------------- merchant scope */}
      <section className="surface-card p-4" aria-label="Merchant">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <Building2 className="size-4 text-muted-foreground" aria-hidden />
          Merchant scope
        </h2>
        <p className="mt-1 text-xs text-muted-foreground">
          Nearly every route in contract 8.1 is merchant-scoped. The selection is shared by the
          whole console and persisted under <span className="mono-id">{UI_STORAGE_KEY}</span>.
        </p>

        {merchantsQuery.isLoading ? (
          <LoadingState variant="rows" count={3} className="mt-3" label="Loading merchants" />
        ) : merchantsQuery.isError ? (
          <ErrorState
            className="mt-3"
            compact
            error={merchantsQuery.error}
            onRetry={() => void merchantsQuery.refetch()}
          />
        ) : (
          <ul className="mt-3 grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
            {merchants.map((item) => {
              const selected = item.merchantId === merchantId;
              return (
                <li key={item.merchantId}>
                  <button
                    type="button"
                    onClick={() => setMerchantId(item.merchantId)}
                    aria-pressed={selected}
                    className={cn(
                      'w-full rounded-lg border p-3 text-left transition-colors',
                      selected
                        ? 'border-primary bg-primary/5'
                        : 'border-border hover:bg-accent/50',
                    )}
                  >
                    <span className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-medium text-foreground">
                        {item.displayName}
                      </span>
                      {selected ? (
                        <Check className="size-4 shrink-0 text-primary" aria-hidden />
                      ) : null}
                    </span>
                    <span className="mono-id mt-0.5 block text-2xs text-muted-foreground">
                      {item.merchantId}
                    </span>
                    <span className="mt-1 flex flex-wrap gap-1.5">
                      <Badge variant="subtle" className="text-2xs">
                        {item.defaultCurrency}
                      </Badge>
                      <Badge variant="subtle" className="text-2xs">
                        {item.country}
                      </Badge>
                      {item.mcc ? (
                        <Badge variant="subtle" className="text-2xs">
                          MCC {item.mcc}
                        </Badge>
                      ) : null}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}

        {merchant ? (
          <>
            <Separator className="my-4" />
            <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2 xl:grid-cols-4">
              <Field label="Legal name">{merchant.legalName}</Field>
              <Field label="Status">{merchant.status}</Field>
              <Field label="Timezone">
                <span className="mono-id text-xs">{merchant.timezone}</span>
              </Field>
              <Field label="Default currency">
                <span className="mono-id text-xs">{merchant.defaultCurrency}</span>
              </Field>
              <Field label="Contact">
                {merchant.contactEmail ?? <span className="text-muted-foreground">-</span>}
              </Field>
              <Field label="Baseline win rate">
                <span className="tabular">{formatBps(merchant.baselineWinRateBps)}</span>
              </Field>
              <Field label="Onboarded">
                <TimestampDisplay value={merchant.onboardedAt} mode="absolute" className="text-xs" />
              </Field>
              <Field label="Currency exponent note">
                <span className="text-2xs text-muted-foreground">
                  Money renders from minor units; JPY has 0 decimals, KWD has 3.
                </span>
              </Field>
            </dl>
          </>
        ) : null}
      </section>

      {/* ---------------------------------------------------------------- endpoints */}
      <section className="surface-card p-4" aria-label="Service endpoints">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <Plug className="size-4 text-muted-foreground" aria-hidden />
          Service endpoints
        </h2>
        <p className="mt-1 text-xs text-muted-foreground">
          Inlined at build time from <span className="mono-id">NEXT_PUBLIC_*</span> (contract 15).
          Changing one means rebuilding the image, or passing a different build arg to the
          Dockerfile - a running page cannot re-point itself.
        </p>

        <dl className="mt-3 space-y-2">
          <EndpointRow
            label="REST base"
            envVar="NEXT_PUBLIC_API_BASE_URL"
            value={config.apiBaseUrl}
            note="api-gateway-service, contract 8.1."
          />
          <EndpointRow
            label="WebSocket"
            envVar="NEXT_PUBLIC_WS_URL"
            value={config.wsUrl}
            note={`Control-tower stream. Currently ${CONNECTION_LABEL[socketStatus].toLowerCase()}.`}
          />
          <EndpointRow
            label="Simulator base"
            envVar="NEXT_PUBLIC_SIM_BASE_URL"
            value={config.simBaseUrl}
            note="simulator-service, contract 8.5. Derived from the gateway host on port 8088 unless set."
          />
          <EndpointRow
            label="SSE base (not wired)"
            envVar="-"
            value={streamUrl('/events')}
            note="Contract 8.1 also defines SSE tails; only the WebSocket is implemented today."
          />
        </dl>
      </section>

      {/* ---------------------------------------------------------------- AI provider */}
      <section className="surface-card p-4" aria-label="AI provider">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <Bot className="size-4 text-muted-foreground" aria-hidden />
          AI provider
        </h2>
        <p className="mt-1 text-xs text-muted-foreground">
          The console never calls a model. Reasoning lives in{' '}
          <span className="mono-id">ai-reasoning-service</span> behind the{' '}
          <span className="mono-id">EvidenceReasoner</span> protocol, and its selection
          (<span className="mono-id">PDEI_AI_PROVIDER=gemini|mock|null</span>, default{' '}
          <span className="mono-id">mock</span> in dev) is a server-side variable that is
          deliberately never shipped to the browser.
        </p>

        <div className="mt-3 flex flex-wrap gap-2">
          <Button variant="outline" size="sm" asChild>
            <a href="http://localhost:8000/v1/providers" target="_blank" rel="noreferrer">
              GET /v1/providers
              <ExternalLink className="size-3.5" />
            </a>
          </Button>
          <Button variant="outline" size="sm" asChild>
            <a href="http://localhost:8000/v1/tools" target="_blank" rel="noreferrer">
              Tool manifest
              <ExternalLink className="size-3.5" />
            </a>
          </Button>
          <Button variant="outline" size="sm" asChild>
            <a href="http://localhost:8000/health" target="_blank" rel="noreferrer">
              Health
              <ExternalLink className="size-3.5" />
            </a>
          </Button>
        </div>

        <p className="mt-3 text-xs text-muted-foreground">
          How many cases actually reached a model is a funnel question, and{' '}
          <span className="mono-id">GET /merchants/{'{'}id{'}'}/summary</span> deliberately carries
          no AI counters - it is counts of evidence, disputes, cases and gaps, nothing else. The
          admitted / denied / auto-prepared numbers live on{' '}
          <Link href="/observability" className="text-primary underline-offset-4 hover:underline">
            Observability
          </Link>
          , behind <span className="mono-id">GET /metrics/funnel</span>.
        </p>
      </section>

      {/* ---------------------------------------------------------------- mock mode */}
      <section className="surface-card p-4" aria-label="Mock mode">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <FlaskConical className="size-4 text-muted-foreground" aria-hidden />
          Mock mode
        </h2>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <span
            className="inline-flex items-center gap-2 rounded-md border px-3 py-1.5 text-sm font-medium"
            style={{
              color: config.useMocks ? 'var(--chart-7)' : 'var(--status-good)',
              borderColor: `color-mix(in oklab, ${config.useMocks ? 'var(--chart-7)' : 'var(--status-good)'} 40%, transparent)`,
            }}
            role="status"
          >
            {config.useMocks ? 'ON - serving deterministic fixtures' : 'OFF - talking to the platform'}
          </span>
          <span className="mono-id text-2xs text-muted-foreground">
            NEXT_PUBLIC_USE_MOCKS={String(config.useMocks)}
          </span>
        </div>
        <p className="mt-2 text-xs text-muted-foreground">
          With mocks on, <span className="mono-id">src/mocks</span> answers every REST call and
          drives the socket, so the whole console is explorable with the backend down. The flag is
          inlined at build time, so this cannot be a switch on this page - it is a restart:
        </p>
        <CommandRow command="NEXT_PUBLIC_USE_MOCKS=true npm run dev" />
        <CommandRow command="NEXT_PUBLIC_USE_MOCKS=false npm run dev" />
      </section>

      {/* ---------------------------------------------------------------- preferences */}
      <section className="surface-card p-4" aria-label="Console preferences">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <SlidersHorizontal className="size-4 text-muted-foreground" aria-hidden />
          Console preferences
        </h2>
        <p className="mt-1 text-xs text-muted-foreground">
          Stored in this browser only, under <span className="mono-id">{UI_STORAGE_KEY}</span>.
        </p>

        <div className="mt-4 grid gap-5 lg:grid-cols-2">
          <Preference label="Theme" hint="The theme is stamped before first paint, so it never flashes.">
            <div className="flex gap-1.5">
              {THEME_OPTIONS.map((option) => {
                const Icon = option.icon;
                return (
                  <Button
                    key={option.value}
                    size="sm"
                    variant={theme === option.value ? 'secondary' : 'outline'}
                    onClick={() => setTheme(option.value)}
                    aria-pressed={theme === option.value}
                  >
                    <Icon className="size-3.5" />
                    {option.label}
                  </Button>
                );
              })}
            </div>
          </Preference>

          <Preference label="Table density" hint="Applies to every DataTable in the console.">
            <div className="flex gap-1.5">
              {DENSITY_OPTIONS.map((option) => (
                <Button
                  key={option.value}
                  size="sm"
                  variant={density === option.value ? 'secondary' : 'outline'}
                  onClick={() => setDensity(option.value)}
                  aria-pressed={density === option.value}
                >
                  {option.label}
                </Button>
              ))}
            </div>
          </Preference>

          <Preference
            label="Timestamps"
            hint="UTC is the default because an evidence capture time that shifts with the viewer is an audit problem."
          >
            <div className="flex gap-1.5">
              <Button
                size="sm"
                variant={timeZoneMode === 'utc' ? 'secondary' : 'outline'}
                onClick={() => setTimeZoneMode('utc')}
                aria-pressed={timeZoneMode === 'utc'}
              >
                UTC
              </Button>
              <Button
                size="sm"
                variant={timeZoneMode === 'local' ? 'secondary' : 'outline'}
                onClick={() => setTimeZoneMode('local')}
                aria-pressed={timeZoneMode === 'local'}
              >
                Local
              </Button>
            </div>
          </Preference>

          <Preference label="Rows per page" hint="Default page size for every server-paged list.">
            <div className="flex gap-1.5">
              {PAGE_SIZES.map((size) => (
                <Button
                  key={size}
                  size="sm"
                  variant={pageSize === size ? 'secondary' : 'outline'}
                  onClick={() => setPageSize(size)}
                  aria-pressed={pageSize === size}
                  className="tabular"
                >
                  {size}
                </Button>
              ))}
            </div>
          </Preference>
        </div>

        <Separator className="my-4" />

        <div className="flex flex-wrap items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              resetFilters();
              toast.success('Filters reset');
            }}
          >
            Reset all filters
          </Button>
          <span className="text-2xs text-muted-foreground">
            Clears band, status, reason-code and search filters across the console. The merchant
            selection and these preferences are kept.
          </span>
        </div>
      </section>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-2xs uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 truncate text-sm text-foreground">{children}</dd>
    </div>
  );
}

function Preference({
  label,
  hint,
  children,
}: {
  label: string;
  hint: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-foreground">{label}</p>
      {children}
      <p className="text-2xs text-muted-foreground">{hint}</p>
    </div>
  );
}

function EndpointRow({
  label,
  envVar,
  value,
  note,
}: {
  label: string;
  envVar: string;
  value: string;
  note: string;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-2 rounded-md border border-border p-3">
      <div className="min-w-0">
        <dt className="text-xs font-medium text-foreground">{label}</dt>
        <dd className="mono-id mt-0.5 break-all text-xs text-muted-foreground">{value}</dd>
        <p className="mt-1 text-2xs text-muted-foreground">
          <span className="mono-id">{envVar}</span> · {note}
        </p>
      </div>
      <CopyButton value={value} />
    </div>
  );
}

function CommandRow({ command }: { command: string }) {
  return (
    <div className="mt-2 flex items-center justify-between gap-2 rounded-md border border-border bg-muted/40 p-2">
      <code className="mono-id break-all text-xs text-foreground">{command}</code>
      <CopyButton value={command} />
    </div>
  );
}

function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = React.useState(false);
  return (
    <Button
      variant="ghost"
      size="icon-sm"
      aria-label={`Copy ${value}`}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setCopied(true);
          toast.success('Copied');
          window.setTimeout(() => setCopied(false), 1500);
        } catch {
          toast.error('Clipboard is unavailable in this browser');
        }
      }}
    >
      {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
    </Button>
  );
}
