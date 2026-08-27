'use client';

import { Layers, Lock } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import {
  EmptyState,
  ErrorState,
  formatBytes,
  HashDisplay,
  LoadingState,
  TimestampDisplay,
} from '@/components/shared';
import { objectKeyFilename } from '@/lib/format/id';
import type { EvidenceVersionRecord } from '@/lib/types/evidence';

export interface EvidenceVersionHistoryProps {
  versions: EvidenceVersionRecord[] | undefined;
  currentVersion: number;
  isLoading: boolean;
  error: unknown;
  onRetry: () => void;
}

/**
 * `GET /evidence/{id}/versions` — the append-only version ledger.
 *
 * Every row is a distinct object in MinIO under its own `v{n}/` prefix (contract 11), with its
 * own digest. Superseding an artifact writes a NEW row; it never rewrites an old one, which is
 * what makes the history usable as evidence about the evidence. The list is newest first and
 * says out loud that older versions are still retrievable, because "we replaced it" and "we
 * kept both" are very different answers in a dispute.
 */
export function EvidenceVersionHistory({
  versions,
  currentVersion,
  isLoading,
  error,
  onRetry,
}: EvidenceVersionHistoryProps) {
  if (isLoading) return <LoadingState variant="rows" count={3} />;
  if (error) return <ErrorState error={error} onRetry={onRetry} compact />;

  if (!versions || versions.length === 0) {
    return (
      <EmptyState
        icon={Layers}
        title="No version records"
        description="This artifact has no rows in pdei.evidence_versions yet. The first version is written when the object is stored."
        compact
      />
    );
  }

  const ordered = [...versions].sort((a, b) => b.version - a.version);

  return (
    <div className="space-y-3">
      <p className="flex items-start gap-2 text-xs text-muted-foreground">
        <Lock className="mt-0.5 size-3.5 shrink-0" aria-hidden />
        {ordered.length} immutable version{ordered.length === 1 ? '' : 's'}. Superseding an
        artifact appends a row and stores new bytes under a new key — nothing here is ever
        overwritten or deleted, so every earlier digest stays checkable.
      </p>

      <ol className="relative space-y-0 border-l border-border">
        {ordered.map((version) => {
          const isCurrent = version.version === currentVersion;
          return (
            <li key={version.evidenceVersionId} className="relative pb-5 pl-6 last:pb-0">
              <span
                className="absolute -left-[4.5px] top-1.5 size-[9px] rounded-full ring-2 ring-card"
                style={{
                  backgroundColor: isCurrent ? 'var(--status-good)' : 'var(--status-neutral)',
                }}
                aria-hidden
              />

              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                <span className="text-sm font-semibold text-foreground">v{version.version}</span>
                {isCurrent ? (
                  <Badge variant="primary" className="text-2xs">
                    Current
                  </Badge>
                ) : (
                  <Badge variant="subtle" className="text-2xs">
                    Retained
                  </Badge>
                )}
                <TimestampDisplay value={version.createdAt} className="text-xs text-muted-foreground" />
                <span className="text-xs text-muted-foreground">by {version.createdBy}</span>
              </div>

              <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                <HashDisplay
                  sha256={version.sha256}
                  label={`sha256 of ${version.evidenceId} v${version.version}`}
                />
                <span title={version.objectKey}>{objectKeyFilename(version.objectKey)}</span>
                <span className="tabular">{formatBytes(version.sizeBytes)}</span>
                <span>{version.contentType}</span>
                {version.sourceEventId ? (
                  <span className="mono-id" title="Canonical event this version came from">
                    {version.sourceEventId}
                  </span>
                ) : null}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
