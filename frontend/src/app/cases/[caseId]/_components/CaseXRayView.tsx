'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import {
  ArrowLeft,
  Bot,
  FileArchive,
  History,
  LayoutList,
  Network,
  RefreshCw,
  ShieldCheck,
  Stethoscope,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ErrorState } from '@/components/shared/ErrorState';
import { LoadingState } from '@/components/shared/LoadingState';
import { PageHeader } from '@/components/shared/PageHeader';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { CopyableId } from '@/components/shared/CopyableId';
import { casesApi, investigationsApi, policiesApi } from '@/lib/api/endpoints';
import { isApiError } from '@/lib/api/client';
import { queryKeys } from '@/lib/query/keys';
import { deadlineState } from '@/lib/format/date';
import type { PolicyView } from '@/lib/types/policy';
import { OverviewTab } from './OverviewTab';
import { TimelineTab } from './TimelineTab';
import { EvidenceTab } from './EvidenceTab';
import { GraphTab } from './GraphTab';
import { AiReasoningTab } from './AiReasoningTab';
import { SafetyGateTab } from './SafetyGateTab';
import { PackageTab } from './PackageTab';
import { CaseActions } from './CaseActions';
import { isBypassed } from './aiBypass';

const TABS = [
  { id: 'overview', label: 'Overview', icon: LayoutList },
  { id: 'timeline', label: 'Timeline', icon: History },
  { id: 'evidence', label: 'Evidence', icon: Stethoscope },
  { id: 'graph', label: 'Graph', icon: Network },
  { id: 'ai', label: 'AI reasoning', icon: Bot },
  { id: 'gate', label: 'Safety gate', icon: ShieldCheck },
  { id: 'package', label: 'Package', icon: FileArchive },
] as const;

type TabId = (typeof TABS)[number]['id'];

const DEFAULT_TAB: TabId = 'overview';

function isTabId(value: string | null): value is TabId {
  return value !== null && TABS.some((tab) => tab.id === value);
}

export interface CaseXRayViewProps {
  caseId: string;
}

/**
 * The Case X-Ray.
 *
 * One payload (`GET /cases/{caseId}/xray`) backs every tab, so switching tabs is free and the
 * seven views can never disagree with each other about the same case. Three small side calls
 * fill gaps the X-Ray does not carry: the `CaseView` header (workflow id, assignment), the
 * applicable policy (the confidence floor and the gate's thresholds), and the stored
 * `InvestigationRecord` (the platform's own admission decision).
 */
export function CaseXRayView({ caseId }: CaseXRayViewProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const tabParam = searchParams.get('tab');
  const activeTab: TabId = isTabId(tabParam) ? tabParam : DEFAULT_TAB;

  const xrayQuery = useQuery({
    queryKey: queryKeys.cases.xray(caseId),
    queryFn: ({ signal }) => casesApi.xray(caseId, signal),
  });

  const caseQuery = useQuery({
    queryKey: queryKeys.cases.detail(caseId),
    queryFn: ({ signal }) => casesApi.get(caseId, signal),
  });

  const xray = xrayQuery.data;

  const policiesQuery = useQuery({
    queryKey: queryKeys.policies.list({ merchantId: xray?.merchantId }),
    // `GET /policies` requires merchantId and returns a bare array, not a page envelope.
    queryFn: ({ signal }) => policiesApi.list({ merchantId: xray?.merchantId as string }, signal),
    enabled: Boolean(xray?.merchantId),
    staleTime: 5 * 60_000,
  });

  const investigationId = xray?.investigation?.investigationId ?? null;
  const investigationQuery = useQuery({
    queryKey: queryKeys.investigations.detail(investigationId ?? 'none'),
    queryFn: ({ signal }) => investigationsApi.get(investigationId as string, signal),
    enabled: Boolean(investigationId),
    // A missing investigation record is not an error worth retrying: the AI reasoning tab
    // falls back to reconstructing the short-circuit from the case state.
    retry: false,
  });

  // The X-Ray usually carries the manifest; ask for it separately only when it does not.
  const needsManifest = Boolean(
    xray && !xray.packageManifest && ['PREPARED', 'SUBMITTED', 'CLOSED'].includes(xray.caseStatus),
  );
  const manifestQuery = useQuery({
    queryKey: queryKeys.cases.packageManifest(caseId),
    queryFn: ({ signal }) => casesApi.packageManifest(caseId, signal),
    enabled: needsManifest,
    retry: false,
  });

  const policy: PolicyView | null = React.useMemo(() => {
    const items = policiesQuery.data ?? [];
    if (items.length === 0 || !xray) return null;
    return (
      items.find((item) => item.reasonCode === xray.reasonCode) ??
      items.find((item) => item.defaultPolicy) ??
      null
    );
  }, [policiesQuery.data, xray]);

  const setTab = (value: string) => {
    const next = new URLSearchParams(searchParams.toString());
    if (value === DEFAULT_TAB) next.delete('tab');
    else next.set('tab', value);
    const query = next.toString();
    router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
  };

  if (xrayQuery.isLoading) {
    return (
      <div className="space-y-5" aria-busy="true">
        <LoadingState variant="panel" label="Loading case" />
        <LoadingState variant="cards" count={4} />
        <LoadingState variant="rows" count={6} />
      </div>
    );
  }

  if (xrayQuery.isError || !xray) {
    const notFound = isApiError(xrayQuery.error) && xrayQuery.error.status === 404;
    return (
      <div className="mx-auto max-w-2xl space-y-4 py-8">
        <ErrorState
          error={xrayQuery.error}
          onRetry={() => void xrayQuery.refetch()}
          title={notFound ? `No case ${caseId}` : 'The Case X-Ray failed to load'}
        />
        <Button variant="outline" asChild>
          <Link href="/cases">
            <ArrowLeft className="size-4" />
            Back to the case queue
          </Link>
        </Button>
      </div>
    );
  }

  const manifest = xray.packageManifest ?? manifestQuery.data ?? null;
  const bypassedAi = isBypassed(xray);
  const deadline = deadlineState(xray.deadlineAt);
  const refreshing = xrayQuery.isFetching || caseQuery.isFetching;

  return (
    <div className="space-y-5">
      <PageHeader
        eyebrow={
          <Link
            href="/cases"
            className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground"
          >
            <ArrowLeft className="size-3" aria-hidden />
            Case queue
          </Link>
        }
        title={<span className="mono-id text-xl">{xray.caseId}</span>}
        description="Everything the platform knows about this case, and everything it decided, on one screen. The tabs share a single payload so they cannot contradict each other."
        meta={
          <>
            <StatusBadge kind="case" value={xray.caseStatus} />
            <StatusBadge kind="dispute" value={xray.disputeStatus} />
            {xray.safetyVerdict ? (
              <StatusBadge kind="safety" value={xray.safetyVerdict.decision} />
            ) : null}
            {bypassedAi ? (
              <Badge variant="subtle" className="gap-1">
                <Bot className="size-3" aria-hidden />
                no model call
              </Badge>
            ) : null}
            {deadline?.urgent ? (
              <Badge
                variant="outline"
                className="border-[color:var(--status-warning)]/50 text-[color:var(--status-warning)]"
              >
                {deadline.label}
              </Badge>
            ) : null}
          </>
        }
        actions={
          <>
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                void xrayQuery.refetch();
                void caseQuery.refetch();
              }}
              disabled={refreshing}
            >
              <RefreshCw className={cn('size-3.5', refreshing && 'animate-spin')} />
              Refresh
            </Button>
            <CaseActions
              caseId={xray.caseId}
              merchantId={xray.merchantId}
              status={xray.caseStatus}
              disputeAmount={xray.disputeAmount}
              packageVersion={manifest?.packageVersion ?? caseQuery.data?.packageVersion ?? null}
              hasPackage={manifest !== null}
            />
          </>
        }
      />

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-2xs text-muted-foreground">
        <span className="inline-flex items-center gap-1">
          dispute <CopyableId id={xray.disputeId} shorten />
        </span>
        <span className="inline-flex items-center gap-1">
          transaction <CopyableId id={xray.transactionId} shorten />
        </span>
        <span className="inline-flex items-center gap-1">
          merchant <span className="mono-id">{xray.merchantId}</span>
        </span>
        {xray.auditEventIds.length > 0 ? (
          <span>{xray.auditEventIds.length} audit entries</span>
        ) : null}
      </div>

      <Tabs value={activeTab} onValueChange={setTab}>
        <TabsList className="h-auto flex-wrap">
          {TABS.map((tab) => {
            const Icon = tab.icon;
            return (
              <TabsTrigger key={tab.id} value={tab.id}>
                <Icon className="size-3.5" aria-hidden />
                {tab.label}
              </TabsTrigger>
            );
          })}
        </TabsList>

        <TabsContent value="overview">
          <OverviewTab xray={xray} caseView={caseQuery.data} bypassedAi={bypassedAi} />
        </TabsContent>

        <TabsContent value="timeline">
          <TimelineTab entries={xray.timeline} />
        </TabsContent>

        <TabsContent value="evidence">
          <EvidenceTab xray={xray} />
        </TabsContent>

        <TabsContent value="graph">
          <GraphTab xray={xray} />
        </TabsContent>

        <TabsContent value="ai">
          <AiReasoningTab
            xray={xray}
            admission={investigationQuery.data?.admission ?? null}
            policy={policy}
            bypassedAi={bypassedAi}
          />
        </TabsContent>

        <TabsContent value="gate">
          <SafetyGateTab xray={xray} policy={policy} />
        </TabsContent>

        <TabsContent value="package">
          <PackageTab manifest={manifest} caseId={xray.caseId} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
