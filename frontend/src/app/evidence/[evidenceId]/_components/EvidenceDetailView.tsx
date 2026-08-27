'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Download, ShieldAlert, ShieldCheck } from 'lucide-react';
import {
  CopyableId,
  DetailList,
  ErrorState,
  EvidenceTypeIcon,
  EVIDENCE_TYPE_LABEL,
  formatBytes,
  LoadingState,
  PageHeader,
  StatusBadge,
  TimestampDisplay,
  type DetailItem,
} from '@/components/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { evidenceApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum, objectKeyFilename } from '@/lib/format/id';
import { formatRatio } from '@/lib/format/money';
import { EvidenceIntegrityPanel } from './EvidenceIntegrityPanel';
import { EvidenceVersionHistory } from './EvidenceVersionHistory';
import { EvidenceLineagePanel } from './EvidenceLineagePanel';

export interface EvidenceDetailViewProps {
  evidenceId: string;
}

/**
 * `/evidence/[evidenceId]` (contract 14).
 *
 * The page answers one question in four parts: *can this artifact be trusted?*
 *   provenance — where did it come from, and when did we see it?
 *   integrity  — do the stored bytes still hash to what we recorded?
 *   versions   — what did it look like before, and is that history intact?
 *   lineage    — what is it derived from, and what does it conflict with?
 */
export function EvidenceDetailView({ evidenceId }: EvidenceDetailViewProps) {
  const evidenceQuery = useQuery({
    queryKey: queryKeys.evidence.detail(evidenceId),
    queryFn: ({ signal }) => evidenceApi.get(evidenceId, signal),
  });

  const versionsQuery = useQuery({
    queryKey: queryKeys.evidence.versions(evidenceId),
    queryFn: ({ signal }) => evidenceApi.versions(evidenceId, signal),
  });

  const lineageQuery = useQuery({
    queryKey: queryKeys.evidence.lineage(evidenceId),
    queryFn: ({ signal }) => evidenceApi.lineage(evidenceId, signal),
  });

  const evidence = evidenceQuery.data;

  if (evidenceQuery.isLoading) {
    return (
      <div aria-busy="true">
        <LoadingState variant="panel" />
        <LoadingState variant="rows" count={6} className="mt-6" />
      </div>
    );
  }

  if (evidenceQuery.isError || !evidence) {
    return (
      <>
        <PageHeader eyebrow={<BackLink />} title={evidenceId} description="Evidence detail." />
        <ErrorState
          error={evidenceQuery.error}
          onRetry={() => void evidenceQuery.refetch()}
          title="Could not load this artifact"
        />
      </>
    );
  }

  const provenance: DetailItem[] = [
    {
      label: 'Source',
      value: humanizeEnum(evidence.source),
      hint: 'The system that produced this artifact (contract 6 EvidenceSource).',
    },
    {
      label: 'Source event',
      value: evidence.sourceEventId ? (
        <span className="mono-id break-all">{evidence.sourceEventId}</span>
      ) : (
        <span className="text-muted-foreground">None recorded</span>
      ),
      hint: evidence.sourceEventId
        ? 'The canonical event this artifact was derived from.'
        : 'Without a source event the provenance cannot be walked back — this is what UNVERIFIABLE_PROVENANCE reports.',
    },
    {
      label: 'Created',
      value: <TimestampDisplay value={evidence.createdAt} mode="absolute" />,
      hint: 'When the artifact came into being.',
    },
    {
      label: 'Observed',
      value: <TimestampDisplay value={evidence.observedAt} mode="absolute" />,
      hint: 'When this platform saw it. A large gap means a late arrival.',
    },
    {
      label: 'Expires',
      value: evidence.expiresAt ? (
        <TimestampDisplay value={evidence.expiresAt} mode="absolute" />
      ) : (
        <span className="text-muted-foreground">Never</span>
      ),
      hint: 'Derived from the policy max age for this evidence type.',
    },
    {
      label: 'Provenance',
      value: evidence.provenanceVerified ? (
        <span
          className="inline-flex items-center gap-1.5 font-medium"
          style={{ color: 'var(--status-good)' }}
        >
          <ShieldCheck className="size-4" aria-hidden />
          Verified
        </span>
      ) : (
        <span
          className="inline-flex items-center gap-1.5 font-medium"
          style={{ color: 'var(--status-serious)' }}
        >
          <ShieldAlert className="size-4" aria-hidden />
          Unverified
        </span>
      ),
      hint: `Extraction quality ${formatRatio(evidence.qualityScore)}`,
    },
    {
      label: 'Transaction',
      value: <CopyableId id={evidence.transactionId} />,
      hint: `Merchant ${evidence.merchantId}`,
    },
    {
      label: 'Related entity',
      value: evidence.relatedEntityId ? (
        <CopyableId id={evidence.relatedEntityId} />
      ) : (
        <span className="text-muted-foreground">—</span>
      ),
      hint: evidence.parentEvidenceId
        ? `Supersedes ${evidence.parentEvidenceId}`
        : 'The order, shipment or payment this artifact evidences.',
    },
    {
      label: 'Stored object',
      value: <span className="mono-id break-all text-xs">{evidence.objectKey}</span>,
      hint: `${objectKeyFilename(evidence.objectKey)} · ${evidence.contentType} · ${formatBytes(evidence.sizeBytes)}`,
      wide: true,
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow={<BackLink />}
        title={
          <span className="inline-flex items-center gap-2">
            <EvidenceTypeIcon type={evidence.type} className="size-5" />
            {EVIDENCE_TYPE_LABEL[evidence.type] ?? humanizeEnum(evidence.type)}
          </span>
        }
        description={evidence.summary ?? 'No summary was extracted for this artifact.'}
        meta={
          <>
            <StatusBadge kind="evidence" value={evidence.status} />
            <Badge variant="subtle">v{evidence.version}</Badge>
            <CopyableId id={evidence.evidenceId} link={false} />
          </>
        }
        actions={
          <Button variant="outline" asChild>
            {/*
              `GET /evidence/{id}/download` answers 302 to a presigned MinIO URL. A cross-origin
              redirect's Location is not readable by fetch, so the browser follows it directly.
            */}
            <a
              href={evidenceApi.downloadUrl(evidence.evidenceId)}
              target="_blank"
              rel="noopener noreferrer"
            >
              <Download className="size-4" aria-hidden />
              Download
            </a>
          </Button>
        }
      />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <Card className="p-5">
          <h2 className="pb-4 text-sm font-semibold tracking-tight">Provenance</h2>
          <DetailList items={provenance} />
        </Card>

        <EvidenceIntegrityPanel evidence={evidence} />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card className="p-5">
          <h2 className="pb-4 text-sm font-semibold tracking-tight">Version history</h2>
          <EvidenceVersionHistory
            versions={versionsQuery.data}
            currentVersion={evidence.version}
            isLoading={versionsQuery.isLoading}
            error={versionsQuery.isError ? versionsQuery.error : undefined}
            onRetry={() => void versionsQuery.refetch()}
          />
        </Card>

        <Card className="p-5">
          <h2 className="pb-4 text-sm font-semibold tracking-tight">Lineage</h2>
          <EvidenceLineagePanel
            lineage={lineageQuery.data}
            evidenceId={evidence.evidenceId}
            isLoading={lineageQuery.isLoading}
            error={lineageQuery.isError ? lineageQuery.error : undefined}
            onRetry={() => void lineageQuery.refetch()}
          />
        </Card>
      </div>
    </>
  );
}

function BackLink() {
  return (
    <Link
      href="/evidence"
      className="inline-flex items-center gap-1 underline-offset-4 hover:text-foreground hover:underline"
    >
      <ArrowLeft className="size-3" aria-hidden />
      Evidence explorer
    </Link>
  );
}
