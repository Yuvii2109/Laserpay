'use client';

import Link from 'next/link';
import { ArrowUpRight, ShieldAlert, ShieldCheck } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { EvidenceTypeIcon, EVIDENCE_TYPE_LABEL } from './EvidenceTypeIcon';
import { StatusBadge } from './StatusBadge';
import { TimestampDisplay } from './TimestampDisplay';
import { HashDisplay } from './HashDisplay';
import { formatRatio } from '@/lib/format/money';
import { humanizeEnum, objectKeyFilename } from '@/lib/format/id';
import type { EvidenceView } from '@/lib/types/evidence';

export interface EvidenceCardProps {
  evidence: EvidenceView;
  className?: string;
  /** Show the owning transaction id (evidence grids that span transactions). */
  showTransaction?: boolean;
  /** Highlight ring, e.g. the artifact a gap or a citation points at. */
  highlighted?: boolean;
}

/** Byte sizes are counts, not money: plain decimal units, no currency machinery. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unitIndex]}`;
}

/**
 * One evidence artifact as a card: what it is, whether it still counts, which version this is,
 * and enough of its digest to compare by eye.
 *
 * The version number and the hash prefix are both on the face of the card on purpose - contract
 * 11 stores every version under its own key, so "v3, a3f9…c1" is the only pair that identifies
 * the exact bytes an operator is looking at.
 */
export function EvidenceCard({
  evidence,
  className,
  showTransaction = false,
  highlighted = false,
}: EvidenceCardProps) {
  const href = `/evidence/${evidence.evidenceId}`;

  return (
    <Card
      className={cn(
        'flex flex-col gap-3 p-4 transition-colors hover:border-foreground/20',
        highlighted && 'ring-2 ring-ring',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-start gap-2">
          <EvidenceTypeIcon type={evidence.type} className="mt-0.5" />
          <div className="min-w-0">
            <Link
              href={href}
              className="block truncate text-sm font-medium text-foreground underline-offset-4 hover:underline"
            >
              {EVIDENCE_TYPE_LABEL[evidence.type] ?? humanizeEnum(evidence.type)}
            </Link>
            <span className="mono-id block truncate text-xs text-muted-foreground">
              {evidence.evidenceId}
            </span>
          </div>
        </div>
        <StatusBadge kind="evidence" value={evidence.status} />
      </div>

      {evidence.summary ? (
        <p className="line-clamp-2 text-sm text-muted-foreground">{evidence.summary}</p>
      ) : null}

      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-muted-foreground">
        <Badge variant="subtle" title="Immutable version of this artifact">
          v{evidence.version}
        </Badge>
        <HashDisplay sha256={evidence.sha256} label={`sha256 of ${evidence.evidenceId}`} />
        <span title={evidence.objectKey} className="truncate">
          {objectKeyFilename(evidence.objectKey)}
        </span>
        <span className="tabular">{formatBytes(evidence.sizeBytes)}</span>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1.5 text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-1.5">
          {evidence.provenanceVerified ? (
            <ShieldCheck className="size-3.5" style={{ color: 'var(--status-good)' }} aria-hidden />
          ) : (
            <ShieldAlert
              className="size-3.5"
              style={{ color: 'var(--status-serious)' }}
              aria-hidden
            />
          )}
          {evidence.provenanceVerified ? 'Provenance verified' : 'Provenance unverified'}
        </span>
        <span title="Extraction quality score">Quality {formatRatio(evidence.qualityScore)}</span>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 border-t border-border pt-2.5 text-xs text-muted-foreground">
        <span>
          {humanizeEnum(evidence.source)} · captured <TimestampDisplay value={evidence.createdAt} />
        </span>
        {evidence.expiresAt ? (
          <span>
            expires <TimestampDisplay value={evidence.expiresAt} />
          </span>
        ) : (
          <span>no expiry</span>
        )}
      </div>

      {showTransaction ? (
        <Link
          href={`/transactions/${evidence.transactionId}`}
          className="mono-id inline-flex items-center gap-1 text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
        >
          {evidence.transactionId}
          <ArrowUpRight className="size-3" aria-hidden />
        </Link>
      ) : null}
    </Card>
  );
}
