'use client';

import * as React from 'react';
import { Badge } from '@/components/ui/badge';
import { EvidenceGraphView } from '@/components/shared/EvidenceGraphView';
import { CopyableId } from '@/components/shared/CopyableId';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import type { CaseXRay } from '@/lib/types/case';

export interface GraphTabProps {
  xray: CaseXRay;
}

/**
 * The evidence graph with the artifacts that actually back this case highlighted.
 *
 * The drawing itself is the shared `EvidenceGraphView` (layered layout, conflict edges, table
 * alternative); this tab only supplies the case-specific question it answers - which nodes are
 * *proof* rather than context.
 *
 * "Supporting" means one of two things, and both are listed separately: the artifact was cited
 * by the investigation (`supportingEvidence`), or it was written into the representment package.
 * An artifact in the graph that is in neither set is context, not proof.
 */
export function GraphTab({ xray }: GraphTabProps) {
  const supportingFromAi = React.useMemo(
    () => new Set(xray.investigation?.supportingEvidence ?? []),
    [xray.investigation],
  );
  const supportingFromPackage = React.useMemo(
    () => new Set((xray.packageManifest?.items ?? []).map((item) => item.evidenceId)),
    [xray.packageManifest],
  );
  const highlightedIds = React.useMemo(() => {
    const merged = new Set<string>(supportingFromAi);
    for (const id of supportingFromPackage) merged.add(id);
    return [...merged];
  }, [supportingFromAi, supportingFromPackage]);

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_19rem]">
      <div className="min-w-0 space-y-2">
        <p className="text-xs text-muted-foreground">
          Highlighted nodes are the ones this case rests on. Everything else is the surrounding
          entity chain the state builder linked to{' '}
          <span className="mono-id">{xray.transactionId}</span>.
          {xray.graph ? (
            <>
              {' '}
              Generated <TimestampDisplay value={xray.graph.generatedAt} className="text-xs" />.
            </>
          ) : null}
        </p>
        <EvidenceGraphView graph={xray.graph} highlightedIds={highlightedIds} />
      </div>

      <aside className="space-y-4">
        <section className="surface-card p-4" aria-label="Supporting evidence">
          <h3 className="text-sm font-semibold text-foreground">Supporting evidence</h3>
          {highlightedIds.length === 0 ? (
            <p className="mt-2 text-xs text-muted-foreground">
              Nothing is highlighted: this case has neither a cited investigation nor an assembled
              package yet, so no node has been claimed as proof.
            </p>
          ) : (
            <ul className="mt-2 space-y-1.5 text-xs">
              {highlightedIds.map((evidenceId) => (
                <li key={evidenceId} className="flex flex-wrap items-center gap-1.5">
                  <CopyableId id={evidenceId} shorten />
                  {supportingFromAi.has(evidenceId) ? (
                    <Badge variant="subtle" className="text-2xs">
                      cited
                    </Badge>
                  ) : null}
                  {supportingFromPackage.has(evidenceId) ? (
                    <Badge variant="primary" className="text-2xs">
                      in package
                    </Badge>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="surface-card p-4" aria-label="Contradiction edges">
          <h3 className="text-sm font-semibold text-foreground">Contradictions in the graph</h3>
          {xray.contradictions.length === 0 ? (
            <p className="mt-2 text-xs text-muted-foreground">
              No <span className="mono-id">CONTRADICTS</span> edges. Zero contradictions is a
              precondition for the deterministic auto-prepare path.
            </p>
          ) : (
            <ul className="mt-2 space-y-2 text-xs">
              {xray.contradictions.map((contradiction, index) => (
                <li
                  key={`${contradiction.left ?? 'l'}-${contradiction.right ?? 'r'}-${index}`}
                  className="rounded-md border border-[color:var(--status-critical)]/30 p-2"
                >
                  <span className="font-medium text-foreground">
                    {contradiction.field ?? 'field'} disagrees
                  </span>
                  <span className="mono-id mt-0.5 block text-2xs text-muted-foreground">
                    {contradiction.left ?? '?'} ↔ {contradiction.right ?? '?'}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </aside>
    </div>
  );
}
