'use client';

import Link from 'next/link';
import { ArrowRight, GitBranch } from 'lucide-react';
import {
  CopyableId,
  EmptyState,
  ErrorState,
  LoadingState,
  TimestampDisplay,
} from '@/components/shared';
import { Badge } from '@/components/ui/badge';
import { humanizeEnum } from '@/lib/format/id';
import type { EvidenceLineage } from '@/lib/types/evidence';

export interface EvidenceLineagePanelProps {
  lineage: EvidenceLineage | undefined;
  evidenceId: string;
  isLoading: boolean;
  error: unknown;
  onRetry: () => void;
}

/**
 * `GET /evidence/{id}/lineage` — where this artifact came from and what it is tied to.
 *
 * Two things, kept apart on purpose: the **ancestry** (the version chain walked back to the
 * root artifact) and the **relationships** (SUPERSEDES, DERIVED_FROM, CONTRADICTS and the rest
 * of `EvidenceRelation`). Provenance is a contract 17 rule 8 obligation, and an artifact whose
 * ancestry cannot be walked is exactly what `UNVERIFIABLE_PROVENANCE` costs 20 points for.
 */
export function EvidenceLineagePanel({
  lineage,
  evidenceId,
  isLoading,
  error,
  onRetry,
}: EvidenceLineagePanelProps) {
  if (isLoading) return <LoadingState variant="rows" count={3} />;
  if (error) return <ErrorState error={error} onRetry={onRetry} compact />;

  if (!lineage) {
    return (
      <EmptyState
        icon={GitBranch}
        title="No lineage recorded"
        description="This artifact has no ancestry and no relationships. That is normal for a first-capture artifact created directly from a canonical event."
        compact
      />
    );
  }

  const chain = [...lineage.ancestry];
  if (chain[chain.length - 1] !== evidenceId) chain.push(evidenceId);

  return (
    <div className="space-y-5">
      <section aria-label="Ancestry">
        <h3 className="pb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Ancestry
        </h3>
        {chain.length <= 1 ? (
          <p className="text-sm text-muted-foreground">
            This artifact is the root of its own chain — nothing was superseded to produce it.
          </p>
        ) : (
          <ol className="flex flex-wrap items-center gap-x-1.5 gap-y-2">
            {chain.map((id, index) => (
              <li key={id} className="flex items-center gap-1.5">
                {index > 0 ? (
                  <ArrowRight className="size-3.5 text-muted-foreground" aria-hidden />
                ) : null}
                {id === evidenceId ? (
                  <span className="mono-id rounded-md border border-primary bg-primary/10 px-2 py-0.5 text-xs text-primary">
                    {id}
                  </span>
                ) : (
                  <Link
                    href={`/evidence/${id}`}
                    className="mono-id rounded-md border border-border px-2 py-0.5 text-xs underline-offset-4 hover:underline"
                  >
                    {id}
                  </Link>
                )}
                {index === 0 && chain.length > 1 ? (
                  <Badge variant="subtle" className="text-2xs">
                    root
                  </Badge>
                ) : null}
              </li>
            ))}
          </ol>
        )}
        <p className="mt-2 text-xs text-muted-foreground">
          Root artifact: <CopyableId id={lineage.rootEvidenceId} shorten className="text-xs" />
        </p>
      </section>

      <section aria-label="Relationships">
        <h3 className="pb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Relationships
        </h3>
        {lineage.relationships.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No rows in <span className="mono-id">pdei.evidence_relationships</span> reference this
            artifact.
          </p>
        ) : (
          <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border">
            {lineage.relationships.map((relationship) => {
              const outgoing = relationship.fromEvidenceId === evidenceId;
              const other = outgoing ? relationship.toEvidenceId : relationship.fromEvidenceId;
              const conflict = relationship.relation === 'CONTRADICTS';
              return (
                <li
                  key={relationship.relationshipId}
                  className="flex flex-wrap items-center gap-x-3 gap-y-1 p-3 text-sm"
                >
                  <Badge
                    variant={conflict ? 'outline' : 'subtle'}
                    className="text-2xs"
                    style={conflict ? { color: 'var(--status-critical)' } : undefined}
                  >
                    {humanizeEnum(relationship.relation)}
                  </Badge>
                  <span className="text-xs text-muted-foreground">
                    {outgoing ? 'this artifact →' : '← this artifact'}
                  </span>
                  <Link
                    href={`/evidence/${other}`}
                    className="mono-id text-xs underline-offset-4 hover:underline"
                  >
                    {other}
                  </Link>
                  {relationship.detail ? (
                    <span className="text-xs text-muted-foreground">{relationship.detail}</span>
                  ) : null}
                  <span className="ml-auto text-xs text-muted-foreground">
                    <TimestampDisplay value={relationship.createdAt} />
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <p className="text-xs text-muted-foreground">
        Lineage generated <TimestampDisplay value={lineage.generatedAt} /> by{' '}
        <span className="mono-id">EvidenceLineageService</span>.
      </p>
    </div>
  );
}
